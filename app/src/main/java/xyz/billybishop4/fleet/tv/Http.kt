package xyz.billybishop4.fleet.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class HttpFailure(val status: Int) : IOException("Server returned $status")

/** Bounded requests; cancellation disconnects the socket, not just its caller. */
class Http {
    suspend fun text(url: String, method: String = "GET", timeout: Int = 20000,
        headers: Map<String, String> = emptyMap()): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val future = AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation { connection.get()?.disconnect(); future.get()?.cancel(true) }
        future.set(workers.submit {
            try {
                var address = url; var verb = method; var requestHeaders = headers
                for (redirect in 0..5) {
                    if (!continuation.isActive) return@submit
                    require(isHttp(address)) { "Enter a valid HTTP or HTTPS address." }
                    require(requestHeaders.all { (k, v) -> !k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n') })
                    val c = URL(address).openConnection() as HttpURLConnection
                    connection.set(c)
                    try {
                        if (!continuation.isActive) return@submit
                        c.instanceFollowRedirects = false; c.requestMethod = verb
                        c.connectTimeout = timeout; c.readTimeout = timeout
                        c.setRequestProperty("User-Agent", "FleetTV/0.5 (Android TV)")
                        c.setRequestProperty("Accept-Encoding", "gzip")
                        requestHeaders.forEach { (key, value) -> c.setRequestProperty(key, value) }
                        if (verb == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
                        val status = c.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            if (redirect == 5) throw IOException("Too many redirects")
                            val next = URI(address).resolve(c.getHeaderField("Location") ?: throw IOException("Missing redirect address")).toString()
                            if (!sameOrigin(address, next)) requestHeaders = requestHeaders.filterKeys { it.lowercase() !in setOf("authorization", "cookie", "proxy-authorization") }
                            if (status == 303) verb = "GET"
                            address = next; continue
                        }
                        if (status !in 200..299) throw HttpFailure(status)
                        val input = if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(c.inputStream) else c.inputStream
                        val body = input.use { stream ->
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
                            while (continuation.isActive) {
                                val n = stream.read(buffer); if (n < 0) break
                                if (out.size() + n > MAX_BYTES) throw IOException("Response is too large")
                                out.write(buffer, 0, n)
                            }
                            out.toString("UTF-8")
                        }
                        if (continuation.isActive) continuation.resume(body)
                        return@submit
                    } finally { c.disconnect(); connection.compareAndSet(c, null) }
                }
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        })
    }
    /** Follows redirects like [text] but returns only the final address; the body is never read. */
    suspend fun finalUrl(url: String, timeout: Int = 15000,
        headers: Map<String, String> = emptyMap()): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val future = AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation { connection.get()?.disconnect(); future.get()?.cancel(true) }
        future.set(workers.submit {
            try {
                var address = url; var requestHeaders = headers
                for (redirect in 0..5) {
                    if (!continuation.isActive) return@submit
                    require(isHttp(address)) { "Enter a valid HTTP or HTTPS address." }
                    require(requestHeaders.all { (k, v) -> !k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n') })
                    val c = URL(address).openConnection() as HttpURLConnection
                    connection.set(c)
                    try {
                        if (!continuation.isActive) return@submit
                        c.instanceFollowRedirects = false; c.requestMethod = "GET"
                        c.connectTimeout = timeout; c.readTimeout = timeout
                        c.setRequestProperty("User-Agent", "FleetTV/0.5 (Android TV)")
                        requestHeaders.forEach { (key, value) -> c.setRequestProperty(key, value) }
                        val status = c.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            if (redirect == 5) throw IOException("Too many redirects")
                            val next = URI(address).resolve(c.getHeaderField("Location") ?: throw IOException("Missing redirect address")).toString()
                            if (!sameOrigin(address, next)) requestHeaders = requestHeaders.filterKeys { it.lowercase() !in setOf("authorization", "cookie", "proxy-authorization") }
                            address = next; continue
                        }
                        if (continuation.isActive) continuation.resume(address)
                        return@submit
                    } finally { c.disconnect(); connection.compareAndSet(c, null) }
                }
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        })
    }
    companion object {
        private const val MAX_BYTES = 20 * 1024 * 1024
        private val workers = Executors.newFixedThreadPool(6) { r -> Thread(r, "fleet-http").apply { isDaemon = true } }
    }
}
fun sameOrigin(a: String, b: String): Boolean = runCatching {
    val x = URI(a); val y = URI(b)
    fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
    x.scheme.equals(y.scheme, true) && x.host.equals(y.host, true) && port(x) == port(y)
}.getOrDefault(false)
