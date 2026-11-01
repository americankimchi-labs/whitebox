package com.americankimchilabs.whitebox.server

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Saved dashcam clips, one per capsule save. The phone encrypts each clip before it leaves (a key derived from the
 * capsule's own minute key, so exactly who may open the capsule may watch its clip: the owner, an emergency contact
 * for a crash, whoever holds its share link). The server holds bytes it cannot read, plus where the clip sits on the
 * capsule's time axis.
 *
 * `clips.log` lines: `S|clipId|device|stream|capsule|savedAtMs|bytes|offsetMs|name` and `R|clipId|reason|atMs`.
 */
class ClipStore(dir: File) {
    data class Clip(
        val id: String,
        val device: String,
        val stream: String,
        val capsuleId: String,
        val savedAtMs: Long,
        val bytes: Long,
        /** Where the clip's start sits on the capsule's time axis (ms, wall clock of the car). */
        val offsetMs: Double,
        /** The file name the owner picked (a dashcam names files by its own clock). */
        val name: String,
    )

    private val root = File(dir, "clips").apply { mkdirs() }
    private val logFile = File(root, "clips.log")
    private val clips = LinkedHashMap<String, Clip>()
    private val random = SecureRandom()

    init {
        if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() }.forEach { l ->
            val f = l.split("|")
            when (f[0]) {
                "S" -> clips[f[1]] = Clip(f[1], f[2], f[3], f[4], f[5].toLong(), f[6].toLong(), f[7].toDouble(), f.drop(8).joinToString("|"))
                "R" -> clips.remove(f[1])
            }
        }
    }

    fun newId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))

    /**
     * Stores an encrypted clip read from [input] (at most [maxBytes]; null if it was larger, and nothing is kept).
     * The clip id is chosen by the phone (it is part of the clip's key derivation) and must be new.
     */
    fun save(id: String, device: String, stream: String, capsuleId: String, offsetMs: Double, name: String, input: InputStream, maxBytes: Long, now: Long): Clip? {
        require(id.matches(Regex("[A-Za-z0-9_-]{16,32}")) && id !in clips) { "bad clip id" }
        val tmp = File(root, "$id.tmp")
        var n = 0L
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (n > maxBytes) {
                    out.close()
                    tmp.delete()
                    return null
                }
                out.write(buf, 0, r)
            }
            out.fd.sync()
        }
        tmp.renameTo(file(id))
        val clip = Clip(id, device, stream, capsuleId, now, n, offsetMs, name.replace(Regex("[|\\n\\r]"), "_").take(120))
        append("S|${clip.id}|$device|$stream|$capsuleId|$now|$n|$offsetMs|${clip.name}")
        synchronized(clips) { clips[id] = clip }
        return clip
    }

    fun file(id: String): File = File(root, "$id.bin")

    fun get(id: String): Clip? = synchronized(clips) { clips[id] }

    fun of(device: String, stream: String, capsuleId: String): List<Clip> =
        synchronized(clips) { clips.values.filter { it.device == device && it.stream == stream && it.capsuleId == capsuleId } }

    fun ofDevices(devices: Collection<String>): List<Clip> = synchronized(clips) { clips.values.filter { it.device in devices } }

    fun all(): List<Clip> = synchronized(clips) { clips.values.toList() }

    fun remove(id: String, reason: String, now: Long) {
        synchronized(clips) { clips.remove(id) } ?: return
        file(id).delete()
        append("R|$id|$reason|$now")
    }

    @Synchronized
    private fun append(line: String) {
        FileOutputStream(logFile, true).use {
            it.write((line + "\n").toByteArray())
            it.fd.sync()
        }
    }
}
