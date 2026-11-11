package io.github.archonw.starswipe.storage.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.PreviewSource
import io.github.archonw.starswipe.storage.Signature
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.join
import io.github.archonw.starswipe.storage.nameOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbConfig(
    val host: String, val share: String, val basePath: String,
    val user: String, val password: String, val domain: String?,
)

/** SMB 2/3 über smbj. Signatur = Änderungszeit + Größe (kein ETag). */
class SmbStorage(private val cfg: SmbConfig) : PhotoStorage {
    // 1 MB Lese-Blöcke statt der vom Server erlaubten bis zu 8 MB: über VPN/Mobilfunk sonst sehr träge.
    private val client = SMBClient(SmbjConfig.builder().withTimeout(60, TimeUnit.SECONDS)
        .withReadBufferSize(1 shl 20).build())
    private val lock = Mutex()
    private var session: Session? = null
    private var share: DiskShare? = null

    private fun full(rel: String): String =
        listOf(cfg.basePath.trim('/'), rel.trim('/')).filter { it.isNotEmpty() }.joinToString("\\") { it.replace('/', '\\') }

    /** Nur der Verbindungsaufbau ist gesperrt; Zugriffe laufen parallel über dieselbe Sitzung (smbj multiplext). */
    private suspend fun current(): DiskShare = lock.withLock { share?.takeIf { it.isConnected } ?: connect() }

    private suspend fun <T> withShare(path: String, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val s = try { current() } catch (e: SMBApiException) { throw map(e, path) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            lock.withLock { reset() }; throw StorageException.Unreachable(e)
        }
        try {
            block(s)
        } catch (e: SMBApiException) {
            throw map(e, path)
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            dropIfDead(s); throw StorageException.Unreachable(e)
        } catch (e: RuntimeException) {
            // smbj verpackt Transportfehler teils in RuntimeExceptions
            dropIfDead(s); throw StorageException.Unreachable(e)
        }
    }

    /** Nur eine wirklich getrennte Verbindung verwerfen – sonst reißt ein Fehler alle parallelen Zugriffe mit. */
    private suspend fun dropIfDead(s: DiskShare) {
        if (!s.isConnected) lock.withLock { if (share === s) reset() }
    }

    private fun connect(): DiskShare {
        reset()
        val conn = client.connect(cfg.host)
        val ses = conn.authenticate(AuthenticationContext(cfg.user, cfg.password.toCharArray(), cfg.domain))
        session = ses
        return (ses.connectShare(cfg.share) as DiskShare).also { share = it }
    }

    private fun reset() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.connection?.close() } catch (_: Exception) {}
        share = null; session = null
    }

    private fun map(e: SMBApiException, path: String): StorageException = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_BAD_NETWORK_NAME,
        NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED -> StorageException.Unauthorized()
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE -> StorageException.NotFound(path)
        NtStatus.STATUS_OBJECT_NAME_COLLISION, NtStatus.STATUS_SHARING_VIOLATION -> StorageException.Conflict(path)
        else -> StorageException.Other("SMB ${e.status} for $path")
    }

    override suspend fun list(dir: String): List<Entry> = withShare(dir) { s ->
        s.list(full(dir)).filter { it.fileName != "." && it.fileName != ".." }.map {
            val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            Entry(join(dir, it.fileName), it.fileName, isDir, it.endOfFile,
                Instant.ofEpochMilli(it.lastWriteTime.toEpochMillis()), null, null, null, null)
        }
    }

    override suspend fun read(path: String, maxBytes: Long?): ByteArray = withShare(path) { s ->
        s.openFile(full(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            f.inputStream.use { inp ->
                if (maxBytes == null) inp.readBytes()
                // Nur den Anfang: gezielt lesen, der Datei-Stream würde ganze Blöcke samt Vorauslesen holen.
                else readHead(maxBytes.toInt()) { buf, off, bOff, len -> f.read(buf, off, bOff, len) }
            }
        }
    }

    override suspend fun stat(path: String): Entry? = withShare(path) { s -> statInternal(s, path) }

    private fun statInternal(s: DiskShare, path: String): Entry? {
        val p = full(path)
        if (!s.fileExists(p)) return null
        val info = s.getFileInformation(p)
        return Entry(path, nameOf(path), false, info.standardInformation.endOfFile,
            Instant.ofEpochMilli(info.basicInformation.lastWriteTime.toEpochMillis()), null, null, null, null)
    }

    override suspend fun write(path: String, data: ByteArray, expected: Signature?): Signature = withShare(path) { s ->
        val cur = statInternal(s, path)
        if (expected == null && cur != null) throw StorageException.Conflict(path)
        if (expected != null && (cur == null || !expected.matches(cur))) throw StorageException.Conflict(path)
        val tmp = "$path.tmp"
        s.openFile(full(tmp), EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { f ->
            f.outputStream.use { it.write(data) }
            f.rename(full(path), true)
        }
        statInternal(s, path)!!.signature
    }

    override suspend fun delete(path: String, expected: Signature?) = withShare(path) { s ->
        val cur = statInternal(s, path) ?: throw StorageException.NotFound(path)
        if (expected != null && !expected.matches(cur)) throw StorageException.Conflict(path)
        s.rm(full(path))
    }

    override fun previewRequest(entry: Entry): PreviewSource =
        PreviewSource.Bytes("smb://${cfg.host}/${cfg.share}/${full(entry.path)}:${entry.modified.toEpochMilli()}") { read(entry.path) }

    /** Für Tests: Ordner anlegen (relativ zur Basis). */
    suspend fun mkdirs(dir: String) = withShare(dir) { s ->
        var acc = ""
        for (seg in full(dir).split('\\').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) seg else "$acc\\$seg"
            if (!s.folderExists(acc)) s.mkdir(acc)
        }
    }

    /** Für Tests: die gesamte Basis rekursiv löschen. */
    suspend fun removeBaseRecursively() = withShare("") { s -> s.rmdir(full(""), true) }
}

/** Liest höchstens [max] Bytes vom Dateianfang über [read] (buf, Dateiposition, Pufferposition, Länge) → gelesene Bytes oder -1. */
internal fun readHead(max: Int, read: (ByteArray, Long, Int, Int) -> Int): ByteArray {
    val buf = ByteArray(max)
    var off = 0
    while (off < max) {
        val n = read(buf, off.toLong(), off, max - off)
        if (n <= 0) break
        off += n
    }
    return if (off == max) buf else buf.copyOf(off)
}
