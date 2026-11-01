package com.americankimchilabs.whitebox.server

import java.io.File
import java.io.FileOutputStream

/**
 * Where the server keeps what it must not lose, as append-only line logs named by path (`identity`, `plans`,
 * `clips/clips`, and per stream `streams/<device>/<stream>/records`, `…/seals`, `…/anchors` …) plus small documents
 * (the pseudonymous labeled windows). Everything built on top keeps its state in memory and rebuilds it from here at
 * start, so a store only has to append, read back in order and, rarely, replace a log whole (retention).
 * [FileDurable] keeps them on a local disk; [FirestoreDurable] in Cloud Firestore.
 */
interface Durable {
    /** Appends [lines] to [log] durably, in order, as one write (one document per received batch in Firestore). */
    fun append(log: String, lines: List<String>)

    fun append(log: String, line: String) = append(log, listOf(line))

    /** Every line of [log] in the order appended; empty if it does not exist. */
    fun lines(log: String): List<String>

    fun exists(log: String): Boolean

    /** Replaces [log] whole (retention removed some of it); crash-safe: either the old or the new log remains. */
    fun replace(log: String, lines: List<String>)

    /** Names of the logs under [prefix] (e.g. `streams/`). */
    fun logs(prefix: String): List<String>

    fun putDoc(name: String, text: String)

    fun deleteDoc(name: String)

    fun countDocs(prefix: String): Int
}

/** [Durable] on a local disk: each log a file `<root>/<name>.log`, each append fsync'd. */
class FileDurable(private val root: File) : Durable {
    private fun file(log: String) = File(root, "$log.log")

    @Synchronized
    override fun append(log: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val f = file(log).apply { parentFile.mkdirs() }
        FileOutputStream(f, true).use {
            it.write(lines.joinToString("") { l -> l + "\n" }.toByteArray())
            it.fd.sync()
        }
    }

    @Synchronized
    override fun lines(log: String): List<String> = file(log).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    override fun exists(log: String) = file(log).exists()

    @Synchronized
    override fun replace(log: String, lines: List<String>) {
        val f = file(log).apply { parentFile.mkdirs() }
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(lines.joinToString("") { l -> l + "\n" }.toByteArray())
            it.fd.sync()
        }
        tmp.renameTo(f)
    }

    override fun logs(prefix: String): List<String> {
        val base = File(root, prefix)
        return base.walkTopDown().filter { it.isFile && it.name.endsWith(".log") }
            .map { it.relativeTo(root).path.removeSuffix(".log") }.sorted().toList()
    }

    @Synchronized
    override fun putDoc(name: String, text: String) {
        File(root, name).apply { parentFile.mkdirs() }.writeText(text)
    }

    @Synchronized
    override fun deleteDoc(name: String) {
        File(root, name).delete()
    }

    override fun countDocs(prefix: String) = File(root, prefix).listFiles()?.size ?: 0
}
