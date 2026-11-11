package io.github.archonw.starswipe.storage.dav

import io.github.archonw.starswipe.storage.Entry
import io.github.archonw.starswipe.storage.PreviewSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class NextcloudStorage(val credentials: NcCredentials, http: OkHttpClient) : WebDavStorage(
    credentials.server.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("remote.php/dav/files").addPathSegment(credentials.userId).addPathSegment("")
        .build().toString(),
    credentials.loginName, credentials.appPassword, http,
) {
    override val extraProps = "<oc:fileid/><nc:metadata-photos-original_date_time/>"

    override fun previewRequest(entry: Entry): PreviewSource {
        val id = entry.fileId ?: return super.previewRequest(entry)
        return PreviewSource.Http(
            "${credentials.server.trimEnd('/')}/index.php/core/preview?fileId=$id&x=2048&y=2048&a=1",
            mapOf("Authorization" to dav.auth),
        )
    }
}
