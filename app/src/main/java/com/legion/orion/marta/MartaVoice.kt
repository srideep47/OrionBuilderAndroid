package com.legion.orion.marta

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Call-style voice for Marta.
 *
 * Mic policy:
 *  - Call starts only when the user taps the orb (explicit).
 *  - While in call, Android SpeechRecognizer (on-device preferred) listens and endpoints.
 *  - While Marta speaks, a tiny 16 kHz AudioRecord + energy VAD watches for barge-in.
 *  - User speech during TTS cancels speech + generation and flips back to listening.
 *
 * TTS: device neural voice (Honor/Android 16 is the lightest high-quality option).
 * Sentences are spoken as they complete so audio starts before the full reply is done.
 */
class MartaVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhase(phase: Phase)
        fun onPartialTranscript(text: String)
        fun onFinalTranscript(text: String)
        fun onBargeIn()
        fun onTtsIdle()
        fun onVoiceError(message: String)
        fun onLevel(rms: Float)
    }

    enum class Phase { IDLE, LISTENING, HEARING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val audioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "marta-vad").apply { priority = Thread.MAX_PRIORITY } }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val inCall = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val restartArmed = AtomicBoolean(true)
    @Volatile private var phase: Phase = Phase.IDLE

    private var vadJob: Job? = null
    private var echo: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private val spoken = StringBuilder()
    private val pendingSpeak = StringBuilder()
    private var utteranceSeq = 0
    private var inflightUtterances = 0

    fun startCall() {
        if (inCall.getAndSet(true)) return
        ensureTts()
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                listener.onVoiceError("Speech recognition is not available on this phone")
                stopCall()
                return@post
            }
            startListening()
        }
    }

    fun stopCall() {
        inCall.set(false)
        restartArmed.set(false)
        stopVad()
        stopTtsPlayback()
        main.post { releaseRecognizer() }
        setPhase(Phase.IDLE)
    }

    fun release() {
        stopCall()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        audioExecutor.shutdownNow()
    }

    fun feedText(delta: String) {
        if (delta.isEmpty()) return
        synchronized(pendingSpeak) {
            pendingSpeak.append(delta)
            flushSpeakable(force = false)
        }
    }

    fun finishSpeaking() {
        synchronized(pendingSpeak) { flushSpeakable(force = true) }
        if (inflightUtterances == 0 && speaking.get()) {
            speaking.set(false)
            stopVad()
            listener.onTtsIdle()
            if (inCall.get()) main.postDelayed({ if (inCall.get() && !speaking.get()) startListening() }, 220)
        }
    }

    fun cancelSpeech() {
        stopTtsPlayback()
        stopVad()
        speaking.set(false)
    }

    private fun flushSpeakable(force: Boolean) {
        val buf = pendingSpeak
        if (buf.isEmpty()) return
        val text = buf.toString()
        val split = SPLIT.find(text)
        if (split != null) {
            val sentence = text.substring(0, split.range.last + 1).trim()
            buf.delete(0, split.range.last + 1)
            if (sentence.isNotBlank()) enqueueSpeak(sentence)
            flushSpeakable(force)
        } else if (force) {
            val rest = text.trim()
            buf.setLength(0)
            if (rest.isNotBlank()) enqueueSpeak(rest)
        } else if (text.length > 140) {
            val cut = text.lastIndexOf(' ').takeIf { it > 40 } ?: text.length
            val chunk = text.substring(0, cut).trim()
            buf.delete(0, cut)
            if (chunk.isNotBlank()) enqueueSpeak(chunk)
        }
    }

    private fun enqueueSpeak(sentence: String) {
        spoken.append(sentence).append(' ')
        val engine = tts
        if (engine == null || !ttsReady) return
        if (speaking.compareAndSet(false, true)) {
            setPhase(Phase.SPEAKING)
            startVad()
        }
        val id = "marta-${++utteranceSeq}"
        inflightUtterances++
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        main.post { engine.speak(sentence, TextToSpeech.QUEUE_ADD, params, id) }
    }

    private fun startListening() {
        if (!inCall.get() || speaking.get()) return
        restartArmed.set(true)
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
        }
        setPhase(Phase.LISTENING)
        rec.startListening(intent)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (inCall.get()) setPhase(Phase.LISTENING) }
        override fun onBeginningOfSpeech() { if (inCall.get()) setPhase(Phase.HEARING) }
        override fun onRmsChanged(rmsdB: Float) { listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (!inCall.get()) return
            val retry = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (retry && restartArmed.get() && !speaking.get()) {
                main.postDelayed({ if (inCall.get() && !speaking.get()) startListening() }, 280)
            } else if (!retry) {
                listener.onVoiceError("Mic error $error")
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty() && inCall.get()) listener.onFinalTranscript(text)
            else if (inCall.get() && !speaking.get()) main.postDelayed({ startListening() }, 200)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) listener.onPartialTranscript(text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startVad() {
        if (vadJob?.isActive == true) return
        vadJob = scope.launch(audioDispatcher) {
            val sr = 16_000
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@launch
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            } catch (_: SecurityException) {
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return@launch
            }
            attachFx(rec.audioSessionId)
            rec.startRecording()
            val buf = ShortArray(min / 2)
            var speechMs = 0
            var ignoreMs = 280
            var noise = 350.0
            try {
                while (isActive && speaking.get() && inCall.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val frameMs = n * 1000 / sr
                    if (ignoreMs > 0) { ignoreMs -= frameMs; continue }
                    var acc = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toDouble()
                        acc += s * s
                    }
                    val rms = sqrt(acc / n)
                    noise = noise * 0.97 + rms * 0.03
                    val threshold = (noise * 3.4).coerceAtLeast(900.0)
                    if (rms > threshold) speechMs += frameMs else speechMs = (speechMs - frameMs / 2).coerceAtLeast(0)
                    if (speechMs >= 180) {
                        withContext(Dispatchers.Main) { bargeIn() }
                        break
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                releaseFx()
            }
        }
    }

    private fun bargeIn() {
        if (!speaking.get() && phase != Phase.SPEAKING) return
        cancelSpeech()
        listener.onBargeIn()
        if (inCall.get()) startListening()
    }

    private fun stopVad() {
        vadJob?.cancel()
        vadJob = null
        releaseFx()
    }

    private fun attachFx(sessionId: Int) {
        releaseFx()
        if (AcousticEchoCanceler.isAvailable()) {
            echo = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.also { it.enabled = true }
        }
    }

    private fun releaseFx() {
        echo?.release(); echo = null
        ns?.release(); ns = null
        agc?.release(); agc = null
    }

    private fun stopTtsPlayback() {
        inflightUtterances = 0
        synchronized(pendingSpeak) { pendingSpeak.setLength(0) }
        spoken.setLength(0)
        tts?.stop()
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                listener.onVoiceError("Text-to-speech failed to start")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale.US
            engine.setSpeechRate(0.96f)
            engine.setPitch(1.02f)
            pickVoice(engine)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    duck(true)
                }
                override fun onDone(utteranceId: String?) {
                    inflightUtterances = (inflightUtterances - 1).coerceAtLeast(0)
                    if (inflightUtterances == 0) {
                        duck(false)
                        speaking.set(false)
                        stopVad()
                        listener.onTtsIdle()
                        if (inCall.get()) main.postDelayed({ if (inCall.get() && !speaking.get()) startListening() }, 220)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onDone(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { onDone(utteranceId) }
            })
        }
    }

    private fun pickVoice(engine: TextToSpeech) {
        val voices = engine.voices ?: return
        val scored = voices.mapNotNull { v ->
            if (v.locale.language != "en") return@mapNotNull null
            if (v.isNetworkConnectionRequired) return@mapNotNull null
            var score = 0
            if (v.locale.country.equals("US", true)) score += 3
            if (v.quality >= Voice.QUALITY_VERY_HIGH) score += 6
            else if (v.quality >= Voice.QUALITY_HIGH) score += 4
            else if (v.quality >= Voice.QUALITY_NORMAL) score += 1
            if (v.latency <= Voice.LATENCY_LOW) score += 2
            if (v.name.contains("neural", true) || v.name.contains("natural", true) || v.name.contains("wavenet", true)) score += 5
            if (v.features?.contains("networkTimeoutMs") == true) score -= 2
            score to v
        }.maxByOrNull { it.first } ?: return
        engine.voice = scored.second
    }

    private fun duck(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            if (on) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN,
        )
    }

    private fun releaseRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun setPhase(next: Phase) {
        if (phase == next) return
        phase = next
        listener.onPhase(next)
    }

    companion object {
        private val SPLIT = Regex("""[.!?]["')\]]*\s+""")
    }
}
