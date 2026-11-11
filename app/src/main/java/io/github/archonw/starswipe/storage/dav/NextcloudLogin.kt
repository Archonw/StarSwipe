package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class LoginStart(val loginUrl: String, val pollToken: String, val pollEndpoint: String)

/** [userId] ist der interne Nextcloud-Benutzername (für den DAV-Pfad); [loginName] kann eine E-Mail sein. */
data class NcCredentials(val server: String, val loginName: String, val appPassword: String, val userId: String)

interface LoginFlow {
    suspend fun start(server: String): LoginStart
    suspend fun pollOnce(start: LoginStart): NcCredentials?
}

/** Nextcloud Login Flow v2. */
class NextcloudLogin(private val http: OkHttpClient) : LoginFlow {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(server: String): LoginStart {
        val s = server.trimEnd('/')
        val o = execJson(Request.Builder().url("$s/index.php/login/v2").post(ByteArray(0).toRequestBody()).build())
            ?: throw StorageException.Other("login start failed")
        val poll = o["poll"]!!.jsonObject
        return LoginStart(o.str("login"), poll.str("token"), poll.str("endpoint"))
    }

    /** null, solange der Nutzer im Browser noch nicht zugestimmt hat. */
    override suspend fun pollOnce(start: LoginStart): NcCredentials? {
        val o = execJson(Request.Builder().url(start.pollEndpoint)
            .post(FormBody.Builder().add("token", start.pollToken).build()).build()) ?: return null
        val server = o.str("server").trimEnd('/')
        val login = o.str("loginName")
        val pw = o.str("appPassword")
        val who = execJson(Request.Builder().url("$server/ocs/v2.php/cloud/user?format=json")
            .header("OCS-APIRequest", "true").header("Authorization", Credentials.basic(login, pw, Charsets.UTF_8)).build())
            ?: throw StorageException.Other("user id not available")
        val id = who["ocs"]!!.jsonObject["data"]!!.jsonObject.str("id")
        return NcCredentials(server, login, pw, id)
    }

    suspend fun revoke(c: NcCredentials) {
        execJson(Request.Builder().url("${c.server.trimEnd('/')}/ocs/v2.php/core/apppassword").delete()
            .header("OCS-APIRequest", "true")
            .header("Authorization", Credentials.basic(c.loginName, c.appPassword, Charsets.UTF_8)).build())
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    /** JSON-Objekt bei 2xx, null bei 404; sonst Ausnahme. */
    private suspend fun execJson(req: Request): JsonObject? = withContext(Dispatchers.IO) {
        val resp = try { http.newCall(req).execute() } catch (e: IOException) { throw StorageException.Unreachable(e) }
        resp.use { r ->
            when {
                r.code == 404 -> null
                r.code == 401 || r.code == 403 -> throw StorageException.Unauthorized()
                r.isSuccessful -> json.parseToJsonElement(r.body!!.string().ifBlank { "{}" }).jsonObject
                else -> throw StorageException.Other("HTTP ${r.code}")
            }
        }
    }
}
