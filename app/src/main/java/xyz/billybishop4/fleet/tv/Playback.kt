@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.*
import kotlinx.coroutines.*


internal data class PlaybackSnapshot(val engine:String, val playing:Boolean, val positionMs:Long,
    val width:Int,val height:Int,val audio:Boolean,val dropped:Long=0,val decoder:String="",
    val audioTrack:String="",val subtitleText:String="",val frameRateHint:Float=0f,val forwardCapMiB:Int=0,
    val displayFps:Double=0.0,val effectsActive:Boolean=false)

/**
 * The audio language handed to the track selector. A blank preference means
 * "no choice made" and must still prefer English — passing no preference at
 * all lets the selector fall back to track order/bitrate, which on
 * multi-audio releases often starts on a non-English track.
 */
internal fun preferredAudioLanguage(prefs: Preferences): String = prefs.audioLanguage.ifBlank { "en" }

/** One hardware-decoded player per visible attachment; direct add-on URLs. */
internal class NativePlayback(private val a: MainActivity, private val m: FleetModel, private val original: Watch, private var fullscreen: Boolean = true, private val exit: () -> Unit) {
    val view = FrameLayout(a).apply { setBackgroundColor(INK) }
    var player: ExoPlayer? = null; private set
    var mediaPlayer: Player? = null; private set
    var picture=VideoPicture(); private set
    private var pictureEffect:VideoPictureEffect?=null
    private var pictureDialog:AlertDialog?=null
    private var pictureResult:((String?)->Unit)?=null
    private var pendingPictureFrame=false
    private var savedTrackParameters:TrackSelectionParameters?=null
    private var savedPlaybackParameters:PlaybackParameters?=null
    private var pictureUnsupported:String?=null
    private val pictureMain=Handler(Looper.getMainLooper())
    private var pausedPictureAnchor:Long?=null
    private var pausedPictureRefresh:Runnable?=null
    private var controls: VodControls? = null
    private var buffering: View? = null
    private var exoDecoder=""
    val snapshot: PlaybackSnapshot? get() = player?.let { exo ->
        val audio=exo.currentTracks.groups.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.isSelected }
        val language=audio?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language } }.orEmpty()
        PlaybackSnapshot("ExoPlayer",exo.isPlaying,exo.currentPosition,exo.videoSize.width,exo.videoSize.height,audio!=null,
            exo.videoDecoderCounters?.droppedBufferCount?.toLong()?:0,decoder=exoDecoder,audioTrack=language,
            subtitleText=exo.currentCues.cues.mapNotNull{it.text}.joinToString("\n"),effectsActive=pictureEffect!=null)
    }
    fun setFullscreen(value:Boolean) {
        fullscreen=value
        view.descendantFocusability=if(value)ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.background=a.round(INK,radius=if(value)0 else 12);view.clipToOutline=!value
        if(value)(playerView?:view.findTag("button:Retry"))?.requestFocus()
    }
    private var playerView: PlayerView? = null
    private var session: MediaSession? = null
    private var attached: Watch? = null
    private var resolution: Job? = null
    private var progressJob: Job? = null
    private var running = false
    private var closed = false
    private var transitioning = false
    private var retries = 0
    private var startMs = if (original.live) 0L else m.resume(original.title,original.episode)
    private var playIntent=true
    fun start() { if (running || closed) return; running=true; connect(false) }
    fun stop() { running=false; resolution?.cancel(); resolution=null; detach() }
    fun close() { stop(); closed=true;view.removeAllViews() }
    private fun message(text:String) { view.removeAllViews();view.addView(a.text(text,20f),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER)) }
    private fun connect(refresh:Boolean) {
        resolution?.cancel();detach();message("Connecting…")
        resolution=a.lifecycleScope.launch {
            try {
                if(retries>0)delay((retries*1000L).coerceAtMost(3000))
                val w=if(original.live&&original.provider!=null&&original.channel!=null)m.repository.liveWatch(original.provider,original.channel,refresh) else original
                ensureActive();if(running&&!closed)attach(w)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(running){transitioning=false;if(original.live&&retries<m.preferences.liveRetries){retries++;connect(true)}else fail((e as? UserMessage)?.message?:"Couldn't connect to this channel. Check the provider or try again.")}}
        }
    }
    private fun attach(w:Watch) {
        attached=w;transitioning=false
        val prefs=m.preferences
        val url=w.stream.url
        val p: Player
        view.removeAllViews()
            val source=DefaultHttpDataSource.Factory().setUserAgent(w.headers["User-Agent"]?:"FleetTV/0.11 (Android TV)")
                .setDefaultRequestProperties(w.headers).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
            val tracks=DefaultTrackSelector(a).apply{parameters=buildUponParameters().setPreferredAudioLanguage(preferredAudioLanguage(prefs))
                .setPreferredTextLanguage(prefs.subtitleLanguage.takeIf{it.isNotBlank()}).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!prefs.subtitles).setSelectUndeterminedTextLanguage(prefs.subtitles).build()}
            val builder=ExoPlayer.Builder(a).setMediaSourceFactory(DefaultMediaSourceFactory(source)).setTrackSelector(tracks)
                .setSeekBackIncrementMs(prefs.seekSeconds*1000L).setSeekForwardIncrementMs(prefs.seekSeconds*1000L)
            if(w.live){val min=prefs.liveBufferSeconds*1000;builder.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(min,maxOf(15000,min*2),500,1000).setTargetBufferBytes(24*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())}
            val exo=builder.build();player=exo
            // Do not even set an empty effect list for neutral playback: doing
            // so creates a GPU sink instead of the direct MediaCodec Surface.
            if(!picture.isNeutral) {
                pictureEffect=VideoPictureEffect(picture)
                exo.setVideoEffects(listOf(pictureEffect!!))
            }
            savedTrackParameters?.let { exo.trackSelectionParameters=it }
            exo.addAnalyticsListener(object:AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) {
                    if(player===exo) {
                        exoDecoder=decoderName
                        android.util.Log.i("FleetPlayback","ExoPlayer sourceId=${w.stream.identity} decoder=$decoderName")
                    }
                }
                override fun onVideoInputFormatChanged(eventTime:AnalyticsListener.EventTime,format:Format,decoderReuseEvaluation:DecoderReuseEvaluation?) {
                    val reason=pictureReason(format)
                    if(player===exo && pictureEffect!=null && reason!=null)view.post { rejectPicture(exo,reason) }
                }
                override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long) {
                    if(player===exo && pendingPictureFrame) { pendingPictureFrame=false;pictureResult?.invoke(null) }
                }
            })
            p=if(w.live)LivePlayer(exo)else exo
            p.setAudioAttributes(AudioAttributes.DEFAULT,true)
            val pv=(LayoutInflater.from(a).inflate(R.layout.live_player,null) as PlayerView).apply{
                player=p;useController=false;resizeMode=when(prefs.videoFit){"Fill"->AspectRatioFrameLayout.RESIZE_MODE_FILL;"Zoom"->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FIT}
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable=true;isFocusableInTouchMode=true;tag="native-player"
            };playerView=pv
            view.removeAllViews();view.addView(pv,FrameLayout.LayoutParams(-1,-1));if(fullscreen)pv.requestFocus()
        mediaPlayer=p
        buffering=a.column().apply {
            tag="playback-buffering";gravity=Gravity.CENTER;pad(12);background=a.round(0xB0080808.toInt())
            add(ProgressBar(a),width=a.dp(32),height=a.dp(32));gap(8)
            add(a.text("Buffering…",13f),width=-2)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Buffering video"
            visibility=if(p.playbackState==Player.STATE_READY || p.playbackState==Player.STATE_ENDED)View.GONE else View.VISIBLE
        }.also { view.addView(it,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER)) }
        if(!w.live) {
            controls=VodControls(a,w,p,prefs.seekSeconds,exit,
                focusVideo={ playerView?.requestFocus() },
                tracks={ type,done -> if(mediaPlayer===p)done(exoTracks(p,type)) },
                selectTrack={ type,id -> if(mediaPlayer===p)selectExoTrack(p,type,id) },
                controlsInset={ bottom ->
                    playerView?.subtitleView?.setPadding(0,0,0,if(bottom>0)bottom+a.dp(8) else 0)
                },pictureControls={ showPictureControls() }).also { c ->
                view.addView(c.view,FrameLayout.LayoutParams(-1,-1))
                playerView?.setOnClickListener { c.show() }
                c.show()
            }
        }
        session=MediaSession.Builder(a,p).build()
        p.addListener(object:Player.Listener{
            override fun onPlaybackStateChanged(state:Int){
                if(mediaPlayer!==p)return
                buffering?.visibility=if(state==Player.STATE_BUFFERING)View.VISIBLE else View.GONE
                keepAwake(p)
                if(state==Player.STATE_ENDED){m.saveProgress(w,p.duration.coerceAtLeast(0),p.duration);if(w.live)recover()}
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(mediaPlayer===p)keepAwake(p)}
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                if(mediaPlayer===p && playWhenReady)cancelPictureRefresh()
            }
            override fun onPlayerError(error:PlaybackException) {
                if(mediaPlayer!==p)return
                if(pictureEffect!=null && error.errorCode in listOf(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
                        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)) {
                    // A bounded return to the known direct hardware path if a
                    // device cannot run the optional effects pipeline.
                    view.post { rejectPicture(exo,"Picture controls aren't supported for this video on this device.") }
                } else recover()
            }

        })
        p.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(w.title.name).setSubtitle(w.episode?.label).build()).build(),if(w.live)C.TIME_UNSET else startMs)
        p.setPlaybackParameters(savedPlaybackParameters?:PlaybackParameters(if(w.live)1f else prefs.speed));p.prepare();p.playWhenReady=playIntent
        keepAwake(p)
        progressJob=a.lifecycleScope.launch{
            var lastPosition=p.currentPosition;var movedAt=SystemClock.elapsedRealtime()
            var diagnosticAt=0L
            while(isActive&&mediaPlayer===p){
                delay(4000);val position=p.currentPosition
                if(position>lastPosition+100||!p.playWhenReady)movedAt=SystemClock.elapsedRealtime()
                lastPosition=position;m.saveProgress(w,position,p.duration)
                player?.takeIf{it.playWhenReady && SystemClock.elapsedRealtime()-diagnosticAt>=15000}?.let { exo ->
                    diagnosticAt=SystemClock.elapsedRealtime()
                    android.util.Log.i("FleetPlayback","ExoPlayer sourceId=${w.stream.identity} decoder=$exoDecoder video=${exo.videoSize.width}x${exo.videoSize.height} fps=${exo.videoFormat?.frameRate} bufferedMs=${exo.totalBufferedDuration} buffering=${exo.playbackState==Player.STATE_BUFFERING} dropped=${exo.videoDecoderCounters?.droppedBufferCount}")
                }
                if(p.playWhenReady&&p.playbackState!=Player.STATE_ENDED&&SystemClock.elapsedRealtime()-movedAt>30000){recover();return@launch}
            }
        }
    }
    fun showPictureControls() {
        if(closed || player==null || pictureDialog?.isShowing==true)return
        controls?.dismiss()
        pictureDialog=showPictureControls(a,picture,::setPicture,actualPicture={picture},restoreAnchor=false) {
            pictureDialog=null
            if(!closed && fullscreen) {
                controls?.show(false)
                view.post { (view.findTag("vod-options")?:playerView)?.requestFocus() }
            }
        }
    }
    internal fun setPicture(requested:VideoPicture,done:(String?)->Unit) {
        val exo=player
        if(closed || exo==null) { done("Start a video before adjusting its picture.");return }
        val next=requested.normalized()
        if(next==picture) { done(null);return }
        if(!next.isNeutral) {
            pictureUnsupported?.let { done(it);return }
            val format=exo.videoFormat
            // The replacement player has not rediscovered the same proven
            // SDR stream yet. Continue accepting held-key updates into its
            // pending matrix instead of rolling the controls back.
            if(format==null && !(pendingPictureFrame && pictureEffect!=null)) {
                done("Wait for the video to start, then adjust its picture.");return
            }
            format?.let { pictureReason(it) }?.let { done(it);return }
        }
        val rebuild=picture.isNeutral!=next.isNeutral
        picture=next;pictureResult=done
        if(rebuild) {
            pendingPictureFrame=true
            rebuildForPicture()
        } else {
            pictureEffect?.update(next)
            if(!pendingPictureFrame) {
                if(!exo.playWhenReady)schedulePausedPictureRefresh(exo)
                done(null)
            }
        }
    }
    private fun cancelPictureRefresh() {
        pausedPictureRefresh?.let(pictureMain::removeCallbacks)
        pausedPictureRefresh=null;pausedPictureAnchor=null
    }
    private fun schedulePausedPictureRefresh(expected:ExoPlayer) {
        pausedPictureRefresh?.let(pictureMain::removeCallbacks)
        if(expected.playbackState==Player.STATE_ENDED)return
        val current=expected.currentPosition
        val anchor=pausedPictureAnchor?.takeIf{kotlin.math.abs(current-it)<=1}?:current.also{pausedPictureAnchor=it}
        val end=expected.duration.takeIf{it>1}?.minus(1)?:Long.MAX_VALUE
        val adjacent=if(anchor<end)anchor+1 else (anchor-1).coerceAtLeast(0)
        val target=if(current==anchor)adjacent else anchor
        pausedPictureRefresh=Runnable {
            pausedPictureRefresh=null
            if(player===expected && !expected.playWhenReady && pictureEffect!=null && expected.playbackState!=Player.STATE_ENDED) {
                // A same-position seek is a Media3 no-op. Alternating these
                // exact targets refreshes the paused frame without drift or
                // retaining full-resolution textures during normal playback.
                if(kotlin.math.abs(expected.currentPosition-anchor)>1) {
                    pausedPictureAnchor=null // A newer user seek already refreshed the frame.
                    return@Runnable
                }
                // Re-register a fresh matrix effect for the paused input;
                // a decoded frame alone can otherwise reuse the old graph.
                val refreshed=VideoPictureEffect(picture)
                pictureEffect=refreshed
                expected.setVideoEffects(listOf(refreshed))
                expected.setSeekParameters(SeekParameters.EXACT)
                expected.seekTo(target)
            }
        }.also{pictureMain.postDelayed(it,150)}
    }
    private fun pictureReason(format:Format):String? = when {
        ColorInfo.isTransferHdr(format.colorInfo) -> "Picture controls are available for SDR video. Use the TV's picture settings for HDR."
        format.drmInitData!=null -> "Picture controls aren't available for this protected video."
        else -> null
    }
    private fun rejectPicture(expected:ExoPlayer,reason:String) {
        if(player!==expected || pictureEffect==null)return
        pictureUnsupported=reason;picture=VideoPicture();pendingPictureFrame=false
        val result=pictureResult
        rebuildForPicture()
        result?.invoke(reason)
    }
    private fun rebuildForPicture() {
        val watch=attached?:return
        val exo=player?:return
        savedTrackParameters=exo.trackSelectionParameters
        savedPlaybackParameters=exo.playbackParameters
        detach(keepPictureControls=true)
        attach(watch)
    }
    private fun keepAwake(p:Player){if(p.isPlaying||(p.playWhenReady&&p.playbackState==Player.STATE_BUFFERING))a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    private fun detach(keepPictureControls:Boolean=false){
        cancelPictureRefresh()
        if(!keepPictureControls) { pictureDialog?.dismiss();pictureDialog=null;pictureResult=null;pendingPictureFrame=false }
        progressJob?.cancel();progressJob=null
        controls?.close();controls=null
        buffering=null
        val p=mediaPlayer;mediaPlayer=null;player=null;exoDecoder=""
        if(p!=null)playIntent=p.playWhenReady
        if(p!=null){attached?.let{m.saveProgress(it,p.currentPosition,p.duration)};if(!original.live)startMs=p.currentPosition.coerceAtLeast(0)}
        playerView?.player=null;playerView=null;session?.release();session=null;p?.release();pictureEffect=null;attached=null
        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun recover(){
        if(!running||transitioning)return
        transitioning=true
        if(original.live){if(retries<m.preferences.liveRetries){retries++;connect(true)}else fail("This channel stopped responding. Retry or choose another channel.");return}
        fail("This stream couldn't play. Try again or choose another stream.")
    }
    private fun fail(text:String){
        resolution?.cancel();resolution=null;detach();view.removeAllViews()
        val box=a.column().apply{pad(24);gravity=Gravity.CENTER;add(a.text(text,19f));gap(20)}
        val retry=a.action("Retry") {retries=0;transitioning=false;connect(original.live)}
        box.add(a.row().apply{button(retry);button(a.action(if(original.live)"Back to channels" else "Back to streams"){exit()})})
        view.addView(box,FrameLayout.LayoutParams(a.dp(640).coerceAtMost((view.width.takeIf{it>0}?:a.resources.displayMetrics.widthPixels)-a.dp(24)),-2,Gravity.CENTER))
        if(!fullscreen)return
        view.descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        box.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                if(retry.width>0&&retry.height>0){
                    view.clearFocus()
                    if(retry.requestFocusFromTouch())box.viewTreeObserver.removeOnPreDrawListener(this)
                }
                return true
            }
        })
    }
    fun dispatchKey(event:KeyEvent):Boolean {
        if(!fullscreen)return false
        if(!original.live)return controls?.dispatchKey(event)?:false
        if(mediaPlayer==null)return false
        if(event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND,KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER))return true
        if(event.keyCode==KeyEvent.KEYCODE_MENU) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)showPictureControls()
            return true
        }
        return false
    }
    fun dismissControls():Boolean = controls?.dismiss()==true
}

private fun exoTracks(p:Player,type:Int):List<PlaybackTrack> {
    val result=ArrayList<PlaybackTrack>()
    if(type==C.TRACK_TYPE_TEXT)result+=PlaybackTrack("off","Off",p.currentTracks.groups.none{it.type==type && it.isSelected})
    p.currentTracks.groups.forEachIndexed { groupIndex,group -> if(group.type==type) {
        for(i in 0 until group.length)if(group.isTrackSupported(i)) {
            val format=group.getTrackFormat(i)
            val label=listOfNotNull(format.label?.takeIf{it.isNotBlank()},format.language?.takeIf{it!="und" && it.isNotBlank()},format.codecs)
                .distinct().joinToString(" · ").ifEmpty { (if(type==C.TRACK_TYPE_AUDIO)"Audio" else "Subtitles")+" ${i+1}" }
            result+=PlaybackTrack("$groupIndex:$i",label,group.isTrackSelected(i))
        }
    } }
    return result
}
private fun selectExoTrack(p:Player,type:Int,id:String) {
    val builder=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,id=="off")
    if(id!="off") {
        val parts=id.split(':');if(parts.size!=2)return
        val group=p.currentTracks.groups.getOrNull(parts[0].toIntOrNull()?:return)?:return
        val track=parts[1].toIntOrNull()?:return
        if(group.type!=type || track !in 0 until group.length || !group.isTrackSupported(track))return
        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup,listOf(track)))
    }
    p.trackSelectionParameters=builder.build()
}

/** Live streams expose play/pause only, including to external media controllers. */
private class LivePlayer(player:Player):ForwardingPlayer(player) {
    override fun getAvailableCommands() = super.getAvailableCommands().buildUpon().removeAll(
        Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,Player.COMMAND_SEEK_TO_MEDIA_ITEM,Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
    override fun isCommandAvailable(command:Int)=availableCommands.contains(command)
    override fun seekTo(positionMs:Long) {}
    override fun seekTo(mediaItemIndex:Int,positionMs:Long) {}
    override fun seekBack() {}
    override fun seekForward() {}
    override fun seekToDefaultPosition() {}
    override fun seekToDefaultPosition(mediaItemIndex:Int) {}
    override fun seekToNext() {}
    override fun seekToNextMediaItem() {}
    override fun seekToPrevious() {}
    override fun seekToPreviousMediaItem() {}
}
