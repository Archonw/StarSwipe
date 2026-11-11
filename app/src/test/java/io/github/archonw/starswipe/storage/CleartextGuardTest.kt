package io.github.archonw.starswipe.storage

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CleartextGuardTest {
    private val http = OkHttpClient.Builder().addInterceptor(CleartextGuard).build()

    @Test fun rejectsPublicHttp() {
        try {
            http.newCall(Request.Builder().url("http://cloud.example.de/x").build()).execute()
            fail()
        } catch (e: IOException) {
            assertEquals(CleartextGuard.MESSAGE, e.message)
        }
    }
}
