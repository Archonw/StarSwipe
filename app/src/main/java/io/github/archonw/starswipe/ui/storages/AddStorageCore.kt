package io.github.archonw.starswipe.ui.storages

import io.github.archonw.starswipe.db.StorageLocationDao
import io.github.archonw.starswipe.db.StorageLocationEntity
import io.github.archonw.starswipe.db.StorageType
import io.github.archonw.starswipe.settings.LocalCfg
import io.github.archonw.starswipe.settings.NextcloudCfg
import io.github.archonw.starswipe.settings.Secrets
import io.github.archonw.starswipe.settings.SmbCfg
import io.github.archonw.starswipe.settings.StorageFactory
import io.github.archonw.starswipe.settings.WebDavCfg
import io.github.archonw.starswipe.R
import io.github.archonw.starswipe.storage.CleartextGuard
import io.github.archonw.starswipe.storage.HomeNetwork
import io.github.archonw.starswipe.ui.UiText
import io.github.archonw.starswipe.ui.uiText
import io.github.archonw.starswipe.storage.PhotoStorage
import io.github.archonw.starswipe.storage.StorageException
import io.github.archonw.starswipe.storage.dav.LoginFlow
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class StorageForm(
    val type: StorageType = StorageType.NEXTCLOUD,
    val name: String = "",
    val url: String = "",
    val host: String = "",
    val share: String = "",
    val basePath: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
)

sealed interface ConnectResult {
    data class Saved(val id: Long) : ConnectResult
    data class Error(val message: UiText) : ConnectResult
}

/** Logik zum Anlegen von Speicherorten, ohne Android-UI. */
class AddStorageCore(
    private val dao: StorageLocationDao,
    private val secrets: Secrets,
    private val login: LoginFlow,
    private val pollIntervalMs: Long = 2000,
    private val connect: (StorageLocationEntity, String?) -> PhotoStorage,
) {
    companion object {
        val MSG_UNAUTHORIZED = uiText(R.string.err_login_rejected)
        val MSG_UNREACHABLE = uiText(R.string.err_unreachable)
        val MSG_HTTPS = uiText(R.string.err_https_only)
        val MSG_FIELDS = uiText(R.string.err_fields)
        val MSG_TIMEOUT = uiText(R.string.err_login_timeout)
        val MSG_ADDRESS = uiText(R.string.err_invalid_address)
        private const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L

        fun oneStarDefault(t: StorageType) = t != StorageType.NEXTCLOUD

        fun message(e: Throwable): UiText = when (e) {
            is StorageException.Unauthorized -> MSG_UNAUTHORIZED
            is StorageException.Unreachable -> if (e.cause?.message == CleartextGuard.MESSAGE) MSG_HTTPS else MSG_UNREACHABLE
            is StorageException.NotFound -> uiText(R.string.err_folder_not_found, e.message ?: "")
            else -> UiText.Raw(e.message ?: e.toString())
        }
    }

    private val json = StorageFactory.json

    suspend fun connect(f: StorageForm): ConnectResult {
        val config = when (f.type) {
            StorageType.WEBDAV -> {
                if (f.url.isBlank() || f.user.isBlank()) return ConnectResult.Error(MSG_FIELDS)
                val u = f.url.trim().toHttpUrlOrNull() ?: return ConnectResult.Error(MSG_ADDRESS)
                if (!u.isHttps && !HomeNetwork.allowsCleartext(u.host)) return ConnectResult.Error(MSG_HTTPS)
                json.encodeToString(WebDavCfg.serializer(), WebDavCfg(u.toString(), f.user.trim()))
            }
            StorageType.SMB -> {
                if (f.host.isBlank() || f.share.isBlank() || f.user.isBlank()) return ConnectResult.Error(MSG_FIELDS)
                json.encodeToString(SmbCfg.serializer(), SmbCfg(f.host.trim(), f.share.trim().trim('/', '\\'),
                    f.basePath.trim().replace('\\', '/').trim('/'), f.user.trim(), f.domain.trim().ifEmpty { null }))
            }
            else -> return ConnectResult.Error(UiText.Raw("type ${f.type} is added differently"))
        }
        return testAndSave(f.type, f.name.ifBlank { f.host.ifBlank { f.url } }, config, f.password, folders = emptyList())
    }

    private suspend fun testAndSave(type: StorageType, name: String, config: String, secret: String?, folders: List<String>): ConnectResult {
        val draft = StorageLocationEntity(0, type, name.trim(), config, folders, true, oneStarDefault(type))
        try {
            connect(draft, secret).list("")
        } catch (e: Exception) {
            return ConnectResult.Error(message(e))
        }
        val id = dao.insert(draft)
        if (secret != null) secrets.put(StorageFactory.secretKey(id), secret)
        return ConnectResult.Saved(id)
    }

    /** Startet den Nextcloud-Login, ruft [openBrowser] mit der Login-Adresse auf und wartet auf die Zustimmung. */
    suspend fun loginNextcloud(server: String, name: String, openBrowser: (String) -> Unit): ConnectResult {
        val base = server.trim().let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
        val u = base.toHttpUrlOrNull() ?: return ConnectResult.Error(MSG_ADDRESS)
        if (!u.isHttps && !HomeNetwork.allowsCleartext(u.host)) return ConnectResult.Error(MSG_HTTPS)
        val start = try { login.start(base) } catch (e: Exception) { return ConnectResult.Error(message(e)) }
        openBrowser(start.loginUrl)
        var waited = 0L
        while (waited < LOGIN_TIMEOUT_MS) {
            // Während der Browser offen ist, liegt die App im Hintergrund; einzelne Netzfehler dort sind normal.
            val c = try { login.pollOnce(start) }
                catch (_: StorageException.Unreachable) { null }
                catch (e: Exception) { return ConnectResult.Error(message(e)) }
            if (c != null) {
                val cfg = json.encodeToString(NextcloudCfg.serializer(), NextcloudCfg(c.server, c.loginName, c.userId))
                return testAndSave(StorageType.NEXTCLOUD, name.ifBlank { u.host }, cfg, c.appPassword, emptyList())
            }
            delay(pollIntervalMs); waited += pollIntervalMs
        }
        return ConnectResult.Error(MSG_TIMEOUT)
    }

    /** Lokaler Ordner: die Freigabe selbst ist die Auswahl. */
    suspend fun saveLocal(treeUri: String, name: String): Long {
        val cfg = json.encodeToString(LocalCfg.serializer(), LocalCfg(treeUri))
        return dao.insert(StorageLocationEntity(0, StorageType.LOCAL, name, cfg, listOf(""), true, oneStarDefault(StorageType.LOCAL)))
    }
}
