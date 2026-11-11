package io.github.archonw.starswipe.storage

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Lässt unverschlüsseltes HTTP nur zu Heimnetz-Adressen durch. */
object CleartextGuard : Interceptor {
    const val MESSAGE = "cleartext HTTP only allowed on the home network"
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !HomeNetwork.allowsCleartext(url.host)) throw IOException(MESSAGE)
        return chain.proceed(chain.request())
    }
}
