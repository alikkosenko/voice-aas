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
 * The clips are raw 16-bit mono PCM at 22.05 kHz. Playback deliberately uses the normal media
 * stream first. A number of DiLink builds accept and start an AudioTrack on BYD's private BTTS
 * stream (17), but route no audible sound to the speakers; because Android reports no error, a
 * normal fallback is never triggered. STREAM_MUSIC is consistently audible and still works when
 * the native BYD assistant and its TTS packages are disabled. The private BYD and accessibility
 * routes remain construction fallbacks only.
 */
class BundledAckPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0L)

    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var focusHeld = false

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

            val output = createTrack(pcm.size) ?: run {
                Log.e(TAG, "No usable audio output route for acknowledgement")
                return@execute
            }
            val track = output.track
            currentTrack = track
            acquireAudioFocus(output.stream)

            val played = runCatching {
                @Suppress("DEPRECATION")
                track.setStereoVolume(1.0f, 1.0f)
                track.play()

                var offset = 0
                while (offset < pcm.size && token == generation.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) error("AudioTrack.write returned $written")
                    offset += written
                }
                offset
            }.onFailure {
                Log.e(TAG, "Unable to play bundled acknowledgement on stream=${output.stream}", it)
            }.getOrDefault(0)

            if (played <= 0 || token != generation.get()) {
                releaseTrack(track)
                if (currentTrack === track) currentTrack = null
                releaseAudioFocus()
                return@execute
            }

            val durationMs = ((played.toLong() * 1000L) / BYTES_PER_SECOND)
                .coerceAtLeast(250L) + RELEASE_MARGIN_MS
            handler.postDelayed({
                if (token == generation.get() && currentTrack === track) {
                    currentTrack = null
                    releaseTrack(track)
                    releaseAudioFocus()
                }
            }, durationMs)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        worker.execute {
            releaseCurrent()
            releaseAudioFocus()
        }
    }

    fun shutdown() {
        generation.incrementAndGet()
        worker.execute {
            releaseCurrent()
            releaseAudioFocus()
        }
        worker.shutdown()
    }

    private fun createTrack(pcmBytes: Int): OutputTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(SAMPLE_RATE)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        val bufferSize = maxOf(minBuffer * 2, pcmBytes.coerceAtMost(MAX_BUFFER_BYTES))

        // Normal media is the primary route. On the affected DiLink builds this is the only
        // route that is guaranteed to reach the speakers even though stream 17 reports success.
        val candidates = listOf(
            OutputCandidate(
                stream = AudioManager.STREAM_MUSIC,
                attributes = AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build(),
            ),
            OutputCandidate(
                stream = BYD_STREAM_BTTS,
                attributes = runCatching {
                    AudioAttributes.Builder().setLegacyStreamType(BYD_STREAM_BTTS).build()
                }.getOrNull(),
            ),
            OutputCandidate(
                stream = AudioManager.STREAM_ACCESSIBILITY,
                attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ),
        )

        for (candidate in candidates) {
            val attributes = candidate.attributes ?: continue
            prepareStreamVolume(candidate.stream)
            val track = runCatching {
                AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
            }.onFailure {
                Log.w(TAG, "AudioTrack construction failed stream=${candidate.stream}", it)
            }.getOrNull() ?: continue

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "Acknowledgement route selected stream=${candidate.stream}")
                return OutputTrack(track, candidate.stream)
            }
            releaseTrack(track)
        }
        return null
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

    @Suppress("DEPRECATION")
    private fun acquireAudioFocus(stream: Int) {
        releaseAudioFocus()
        val result = runCatching {
            audioManager.requestAudioFocus(
                null,
                stream,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        runCatching { audioManager.abandonAudioFocus(null) }
    }

    private fun releaseCurrent() {
        val track = currentTrack
        currentTrack = null
        if (track != null) releaseTrack(track)
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private data class OutputCandidate(
        val stream: Int,
        val attributes: AudioAttributes?,
    )

    private data class OutputTrack(
        val track: AudioTrack,
        val stream: Int,
    )

    companion object {
        private const val TAG = "AasBundledAck"
        private const val SAMPLE_RATE = 22_050
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNELS = 1
        private const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS
        private const val RELEASE_MARGIN_MS = 350L
        private const val MAX_BUFFER_BYTES = 128 * 1024
        private const val BYD_STREAM_BTTS = 17
    }
}
