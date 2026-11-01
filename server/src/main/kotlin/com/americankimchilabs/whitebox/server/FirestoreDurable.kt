package com.americankimchilabs.whitebox.server

import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.SetOptions

/**
 * [Durable] in Cloud Firestore (the server on Cloud Run, one instance). Each log is a document in `logs` (its name
 * and the current generation); each append is one document in its `chunks` (a received batch of records is one
 * write), with ids `g<generation>-<n>` so a log reads back in order by id without a composite index. Replacing a log
 * writes the next generation's chunks, then moves the generation in one write, then deletes the old ones: a crash
 * in between leaves the old log or the new one, never a mix. Small documents (labeled windows) live in `docs`.
 *
 * Only this one instance writes, so what it read once stays true: logs are cached after their first read (except the
 * records logs, which the stream store already holds in memory and reads back only at start and on retention).
 * Credentials and project come from the environment (Cloud Run's service account; `FIRESTORE_EMULATOR_HOST` for the
 * local emulator).
 */
class FirestoreDurable(private val db: Firestore = FirestoreOptions.getDefaultInstance().service) : Durable {
    private class Log(val name: String, var gen: Long, var next: Long, var cached: MutableList<String>?)

    private val logs = HashMap<String, Log>()
    private val docNames = HashSet<String>()

    init {
        db.collection("logs").get().get().documents.forEach { d ->
            val name = d.getString("name") ?: return@forEach
            logs[name] = Log(name, d.getLong("gen") ?: 0, d.getLong("next") ?: 0, null)
        }
        db.collection("docs").select("name").get().get().documents.forEach { d -> d.getString("name")?.let(docNames::add) }
    }

    private fun id(name: String) = name.replace("/", "~")
    private fun logRef(name: String) = db.collection("logs").document(id(name))
    private fun chunkId(gen: Long, n: Long) = "g%06d-%012d".format(gen, n)
    private fun cacheable(log: String) = !log.endsWith("/records")

    /** Lines in chunks under Firestore's 1 MiB document limit (with room for field overhead). */
    private fun chunks(lines: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var size = 0
        for (l in lines) {
            val n = l.toByteArray().size + 16
            if (cur.isNotEmpty() && (size + n > CHUNK_BYTES || cur.size >= CHUNK_LINES)) {
                out += cur
                cur = mutableListOf()
                size = 0
            }
            cur += l
            size += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    @Synchronized
    override fun append(log: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val l = logs.getOrPut(log) { Log(log, 0, 0, if (cacheable(log)) mutableListOf() else null) }
        val batch = db.batch()
        for (chunk in chunks(lines)) batch.set(logRef(log).collection("chunks").document(chunkId(l.gen, l.next++)), mapOf("lines" to chunk))
        batch.set(logRef(log), mapOf("name" to log, "gen" to l.gen, "next" to l.next), SetOptions.merge())
        batch.commit().get()
        l.cached?.addAll(lines)
    }

    private fun read(l: Log): List<String> {
        val prefix = "g%06d-".format(l.gen)
        return db.collection("logs").document(id(l.name)).collection("chunks")
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), prefix).whereLessThan(FieldPath.documentId(), "g%06d-".format(l.gen + 1))
            .orderBy(FieldPath.documentId()).get().get().documents
            .flatMap { d -> (d.get("lines") as? List<*>)?.map { it.toString() } ?: emptyList() }
    }

    @Synchronized
    override fun lines(log: String): List<String> {
        val l = logs[log] ?: return emptyList()
        l.cached?.let { return it.toList() }
        val lines = read(l)
        if (cacheable(log)) l.cached = lines.toMutableList()
        return lines
    }

    @Synchronized
    override fun exists(log: String) = log in logs

    @Synchronized
    override fun replace(log: String, lines: List<String>) {
        val l = logs.getOrPut(log) { Log(log, 0, 0, null) }
        val old = l.gen
        val gen = old + 1
        var n = 0L
        for (part in chunks(lines).chunked(BATCH_WRITES)) {
            val batch = db.batch()
            for (chunk in part) batch.set(logRef(log).collection("chunks").document(chunkId(gen, n++)), mapOf("lines" to chunk))
            batch.commit().get()
        }
        logRef(log).set(mapOf("name" to log, "gen" to gen, "next" to n), SetOptions.merge()).get()
        l.gen = gen
        l.next = n
        l.cached = if (cacheable(log)) lines.toMutableList() else null
        // The old generation is no longer read; delete it.
        val stale = logRef(log).collection("chunks").whereLessThan(FieldPath.documentId(), "g%06d-".format(gen)).get().get().documents
        for (part in stale.chunked(BATCH_WRITES)) db.batch().apply { part.forEach { delete(it.reference) } }.commit().get()
    }

    @Synchronized
    override fun logs(prefix: String): List<String> = logs.keys.filter { it.startsWith(prefix) }.sorted()

    @Synchronized
    override fun putDoc(name: String, text: String) {
        db.collection("docs").document(id(name)).set(mapOf("name" to name, "text" to text)).get()
        docNames += name
    }

    @Synchronized
    override fun deleteDoc(name: String) {
        db.collection("docs").document(id(name)).delete().get()
        docNames -= name
    }

    @Synchronized
    override fun countDocs(prefix: String) = docNames.count { it.startsWith("$prefix/") }

    private companion object {
        const val CHUNK_BYTES = 800_000
        const val CHUNK_LINES = 4_000
        const val BATCH_WRITES = 400
    }
}
