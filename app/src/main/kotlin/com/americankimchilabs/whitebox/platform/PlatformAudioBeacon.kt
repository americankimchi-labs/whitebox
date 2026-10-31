package com.americankimchilabs.whitebox.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.americankimchilabs.whitebox.core.AudioBeaconPort
import com.americankimchilabs.whitebox.domain.Chirp
import com.americankimchilabs.whitebox.domain.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

private const val TAG = "WhiteBox/Beacon"

/** The head unit's mixer runs at 48 kHz, so the chirp band up to 24 kHz is representable. */
private const val RATE = 48_000

/**
 * Plays [Chirp] through AudioTrack and returns the measured presentation time of its first frame. The time between
 * play() and the first frame varies (3–25 ms measured on the head unit's emulator), so only the measured time goes
 * into the chain. Sonification usage, no audio focus: it is inaudible and must not duck or pause anything.
 */
class PlatformAudioBeacon : AudioBeaconPort, Closeable {

    private val pcm: ShortArray = Chirp.samples(RATE).let { s -> ShortArray(s.size) { (s[it] * Short.MAX_VALUE).toInt().toShort() } }

    private val track: AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.onFailure { Log.w(TAG, "no audio output for the chirp: ${it.message}") }.getOrNull()

    override suspend fun emitChirp(chirpId: Int): Timestamp? = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext null
        t.stop()
        t.reloadStaticData()
        t.play()
        val ts = AudioTimestamp()
        withTimeoutOrNull(500) {
            while (!(t.getTimestamp(ts) && ts.framePosition > 0)) delay(10)
            // AudioTimestamp is on CLOCK_MONOTONIC; the chain uses elapsed realtime (which also counts suspend).
            val toElapsed = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            val frame0Ns = ts.nanoTime - ts.framePosition * 1_000_000_000L / RATE + toElapsed
            val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - frame0Ns) / 1_000_000
            Timestamp(frame0Ns, wall)
        }.also { if (it == null) Log.w(TAG, "chirp $chirpId: no playback timestamp") }
    }

    override fun close() {
        track?.release()
    }
}
