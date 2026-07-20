package com.aas.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aas.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Guaranteed offline acknowledgement for DiLink units that have no usable Android TTS engine.
 *
 * The four compact PCM clips are bundled in the APK, so a successful command can always say
 * "Готово", and an unsuccessful command can say a short error phrase. Playback targets BYD's
 * dedicated voice stream (17), the same route used by BYDMate; when that route cannot create an
 * AudioTrack, the framework accessibility-speech route is used instead.
 *
 * Detailed Android TextToSpeech remains available in [SpeechOutput], but command completion no
 * longer depends on a vendor/system TTS service being installed or correctly configured.
 */
class BundledAckPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0L)

    @Volatile private var currentTrack: AudioTrack? = null

    fun play(success: Boolean, languageTag: String) {
        val token = generation.incrementAndGet()
        val resource = when {
            languageTag.startsWith("uk", ignoreCase = true) && success -> R.raw.aas_success_uk
            languageTag.startsWith("uk", ignoreCase = true) -> R.raw.aas_error_uk
            success -> R.raw.aas_success_ru
            else -> R.raw.aas_error_ru
        }

        worker.execute {
            releaseCurrent()
            val pcm = runCatching {
                appContext.resources.openRawResource(resource).use { it.readBytes() }
            }.onFailure { Log.e(TAG, "Unable to read bundled acknowledgement", it) }
                .getOrNull() ?: return@execute
            if (token != generation.get()) return@execute

            val track = createTrack(pcm.size) ?: return@execute
            currentTrack = track
            val written = runCatching { track.write(pcm, 0, pcm.size) }
                .onFailure { Log.e(TAG, "Unable to write bundled acknowledgement", it) }
                .getOrDefault(AudioTrack.ERROR)
            if (written <= 0 || token != generation.get()) {
                releaseTrack(track)
                return@execute
            }

            runCatching {
                @Suppress("DEPRECATION")
                track.setStereoVolume(1.0f, 1.0f)
                track.play()
            }.onFailure {
                Log.e(TAG, "Unable to play bundled acknowledgement", it)
                releaseTrack(track)
                return@execute
            }

            val durationMs = ((written.toLong() * 1000L) / BYTES_PER_SECOND)
                .coerceAtLeast(250L) + RELEASE_MARGIN_MS
            handler.postDelayed({
                if (token == generation.get() && currentTrack === track) {
                    currentTrack = null
                    releaseTrack(track)
                }
            }, durationMs)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        worker.execute { releaseCurrent() }
    }

    fun shutdown() {
        generation.incrementAndGet()
        worker.execute { releaseCurrent() }
        worker.shutdown()
    }

    private fun createTrack(pcmBytes: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(SAMPLE_RATE)
            .build()
        val bufferSize = pcmBytes.coerceAtLeast(
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(1)
        )

        prepareStreamVolume(BYD_STREAM_BTTS)
        val bydTrack = runCatching {
            newTrack(
                AudioAttributes.Builder().setLegacyStreamType(BYD_STREAM_BTTS).build(),
                format,
                bufferSize,
            )
        }.getOrNull()?.takeIfInitialized()
        if (bydTrack != null) return bydTrack

        prepareStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
        return runCatching {
            newTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                format,
                bufferSize,
            )
        }.onFailure { Log.e(TAG, "Unable to create acknowledgement AudioTrack", it) }
            .getOrNull()?.takeIfInitialized()
    }

    private fun newTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSize: Int,
    ): AudioTrack = AudioTrack(
        attributes,
        format,
        bufferSize,
        AudioTrack.MODE_STATIC,
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    )

    private fun AudioTrack.takeIfInitialized(): AudioTrack? =
        if (state == AudioTrack.STATE_INITIALIZED) this
        else {
            releaseTrack(this)
            null
        }

    private fun prepareStreamVolume(stream: Int) {
        runCatching {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            if (audioManager.getStreamVolume(stream) <= 0) {
                audioManager.setStreamVolume(stream, (max / 3).coerceAtLeast(1), 0)
            }
        }.onFailure { Log.w(TAG, "Unable to prepare acknowledgement stream=$stream", it) }
    }

    private fun releaseCurrent() {
        val track = currentTrack
        currentTrack = null
        if (track != null) releaseTrack(track)
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    companion object {
        private const val TAG = "AasBundledAck"
        private const val SAMPLE_RATE = 22_050
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNELS = 1
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS
        private const val RELEASE_MARGIN_MS = 250L
        private const val BYD_STREAM_BTTS = 17
    }
}
