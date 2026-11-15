package kr.routerescue.demo

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

internal class PleosSpeech(context: Context, private val status: (String) -> Unit) : AutoCloseable {
    private val tts = TextToSpeech(context.applicationContext,TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext,SttMode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ if (it<0) finish(null) },main).build()
    private var synthesisReady = false
    private var recognitionReady = false
    private var generation = 0
    private var completion: ((String?) -> Unit)? = null
    private var outputListener: EventListener? = null
    private var inputListener: ResultListener? = null
    private var closed = false
    private val timeout = Runnable { finish(null) }

    fun initialize() {
        tts.initialize(object : TtsInit {
            override fun onSuccess() { main.post {
                if (!closed) runCatching { tts.setLocale(Locale.KOREAN); tts.setAudioAttributes(attributes) }
                    .onSuccess { synthesisReady=true; status("Pleos TTS 준비됨") }
                    .onFailure { status("Pleos TTS 설정 실패") }
            } }
            override fun onFailure(errorType: TtsError) { main.post { if (!closed) status("Pleos TTS · $errorType") } }
        })
        stt.initialize(object : SttInit {
            override fun onSuccess() { main.post {
                if (!closed) runCatching { stt.setLocale(Locale.KOREAN) }
                    .onSuccess { recognitionReady=true; status("Pleos STT 준비됨") }
                    .onFailure { status("Pleos STT 설정 실패") }
            } }
            override fun onFailure(errorType: SttError) { main.post { if (!closed) status("Pleos STT · $errorType") } }
        })
    }

    fun ask(message: String, mayListen: () -> Boolean, answer: (String?) -> Unit): Boolean {
        if (!synthesisReady || !recognitionReady || closed || completion!=null) return false
        if (audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val id=++generation
        completion=answer
        var listening=false
        fun dispatch(action: () -> Unit) { main.post { if (id==generation && completion!=null) action() } }
        val input=object : ResultListener {
            override fun onUpdated(stt: String, completed: Boolean) {
                if (completed) dispatch { if (listening) finish(stt) }
            }
            override fun onEndedRecognition() = dispatch { if (listening) finish(null) }
            override fun onError(errorType: SttError) = dispatch { status("Pleos STT · $errorType"); finish(null) }
            override fun onReady() = Unit
            override fun onStartedRecognition() = Unit
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        val output=object : EventListener {
            override fun onDone() = dispatch {
                if (!listening) {
                    if (!mayListen()) finish(null)
                    else {
                        listening=true
                        audio.abandonAudioFocusRequest(focus)
                        runCatching { stt.addListener(input); stt.request() }
                            .onFailure { status("Pleos STT 요청 실패"); finish(null) }
                    }
                }
            }
            override fun onError(errorType: TtsError) = dispatch { status("Pleos TTS · $errorType"); finish(null) }
            override fun onStop() = dispatch { if (!listening) finish(null) }
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        inputListener=input
        outputListener=output
        main.postDelayed(timeout,15000)
        return runCatching { tts.addEventListener(output); tts.speak(message,true) }
            .onFailure { stop() }.isSuccess
    }

    private fun finish(result: String?) {
        val callback=completion ?: return
        stop()
        callback(result)
    }

    fun stop() {
        if (completion==null) return
        completion=null
        generation++
        main.removeCallbacks(timeout)
        outputListener?.let { runCatching { tts.removeEventListener(it) } }
        inputListener?.let { runCatching { stt.removeListener(it) } }
        outputListener=null
        inputListener=null
        runCatching { tts.stop() }
        runCatching { stt.stop() }
        audio.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        closed=true
        stop()
        runCatching { tts.release() }
        runCatching { stt.release() }
    }
}
