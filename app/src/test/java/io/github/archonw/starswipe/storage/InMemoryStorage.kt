package io.github.archonw.starswipe.storage

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Speicher im Arbeitsspeicher für Tests; zählt Lesezugriffe. */
class InMemoryStorage(files: Map<String, ByteArray> = emptyMap()) : PhotoStorage {
    private class F(var data: ByteArray, var modified: Instant, var etag: String)
    private val files = linkedMapOf<String, F>()
    private val clock = AtomicLong(1_700_000_000_000)
    private val etags = AtomicLong(1)
    val reads: MutableList<Pair<String, Long?>> = java.util.Collections.synchronizedList(mutableListOf())
    val writes = mutableListOf<String>()
    var failWith: StorageException? = null

    init { files.forEach { (p, d) -> put(p, d) } }

    fun put(path: String, data: ByteArray) {
        files[path] = F(data, Instant.ofEpochMilli(clock.addAndGet(1000)), "e${etags.getAndIncrement()}")
    }
    fun content(path: String): ByteArray? = files[path]?.data
    fun paths(): Set<String> = files.keys.toSet()

    private fun check() { failWith?.let { throw it } }

    private fun entry(path: String, f: F) = Entry(path, nameOf(path), false, f.data.size.toLong(),
        f.modified, f.etag, null, null, null)

    private fun dirExists(dir: String) = dir.isEmpty() || files.keys.any { it.startsWith("$dir/") }

    override suspend fun list(dir: String): List<Entry> {
        check()
        if (!dirExists(dir)) throw StorageException.NotFound(dir)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = linkedMapOf<String, Entry>()
        for ((p, f) in files) {
            if (!p.startsWith(prefix)) continue
            val rest = p.removePrefix(prefix)
            val child = rest.substringBefore('/')
            val childPath = prefix + child
            if (rest.contains('/')) out.getOrPut(childPath) {
                Entry(childPath, child, true, 0, Instant.EPOCH, null, null, null, null)
            } else out[childPath] = entry(p, f)
        }
        return out.values.toList()
    }

    override suspend fun read(path: String, maxBytes: Long?): ByteArray {
        check()
        reads += path to maxBytes
        val f = files[path] ?: throw StorageException.NotFound(path)
        return if (maxBytes != null && maxBytes < f.data.size) f.data.copyOf(maxBytes.toInt()) else f.data.copyOf()
    }

    override suspend fun stat(path: String): Entry? { check(); return files[path]?.let { entry(path, it) } }

    override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature {
        check()
        val cur = files[path]
        if (expected == null && cur != null) throw StorageException.Conflict(path)
        if (expected != null && (cur == null || !expected.matches(entry(path, cur)))) throw StorageException.Conflict(path)
        put(path, data)
        writes += path
        return entry(path, files[path]!!).signature
    }

    override suspend fun delete(path: String, expected: Signature?) {
        check()
        val cur = files[path] ?: throw StorageException.NotFound(path)
        if (expected != null && !expected.matches(entry(path, cur))) throw StorageException.Conflict(path)
        files.remove(path)
    }

    override fun previewRequest(entry: Entry) = PreviewSource.Bytes("mem:${entry.path}") { read(entry.path) }
}
