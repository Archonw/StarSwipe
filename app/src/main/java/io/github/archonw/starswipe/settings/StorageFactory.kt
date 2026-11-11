package io.github.archonw.starswipe.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.dav.NcCredentials
import io.github.archonw.starswipe.storage.dav.NextcloudStorage
import io.github.archonw.starswipe.storage.dav.WebDavStorage
import io.github.archonw.starswipe.storage.local.LocalStorage
import io.github.archonw.starswipe.storage.smb.SmbConfig
import io.github.archonw.starswipe.storage.smb.SmbStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

@Serializable data class NextcloudCfg(val server: String, val loginName: String, val userId: String)
@Serializable data class WebDavCfg(val url: String, val user: String)
@Serializable data class SmbCfg(val host: String, val share: String, val basePath: String = "", val user: String, val domain: String? = null)
@Serializable data class LocalCfg(val treeUri: String)

/** Baut aus einem gespeicherten Speicherort den passenden [PhotoStorage]. */
class StorageFactory(private val context: Context, private val secrets: Secrets, private val http: OkHttpClient) {
    fun create(loc: StorageLocationEntity): PhotoStorage = create(loc, null)

    /** [secretOverride]: Passwort für den Verbindungstest, bevor es gespeichert ist. */
    fun create(loc: StorageLocationEntity, secretOverride: String?): PhotoStorage {
        fun secret(id: Long): String = secretOverride ?: storedSecret(id)
        return when (loc.type) {
        StorageType.NEXTCLOUD -> json.decodeFromString<NextcloudCfg>(loc.config).let {
            NextcloudStorage(NcCredentials(it.server, it.loginName, secret(loc.id), it.userId), http)
        }
        StorageType.WEBDAV -> json.decodeFromString<WebDavCfg>(loc.config).let { WebDavStorage(it.url, it.user, secret(loc.id), http) }
        StorageType.SMB -> json.decodeFromString<SmbCfg>(loc.config).let {
            SmbStorage(SmbConfig(it.host, it.share, it.basePath, it.user, secret(loc.id), it.domain))
        }
        StorageType.LOCAL -> json.decodeFromString<LocalCfg>(loc.config).let {
            val uri = Uri.parse(it.treeUri)
            if (uri.scheme == "file") LocalStorage(context, DocumentFile.fromFile(File(uri.path!!)))
            else LocalStorage(context, uri)
        }
        }
    }

    private fun storedSecret(id: Long): String = secrets.get(secretKey(id)) ?: throw IllegalStateException("no password stored for location $id")

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun secretKey(id: Long) = "loc-$id-password"
    }
}
