package com.inteldes.app.data.web

/** One public download link of a notulensi, as the server lists it (the secret part of the link is never listed). */
data class ShareLinkInfo(
    val id: String,
    val includeTranscript: Boolean,
    val includeAudio: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val revokedAt: Long?,
    val downloadCount: Int,
)

/** A link just made: [url] is the only time its secret part is visible. */
data class CreatedShare(
    val id: String,
    val includeTranscript: Boolean,
    val includeAudio: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val revokedAt: Long?,
    val downloadCount: Int,
    val url: String,
) {
    fun info() = ShareLinkInfo(id, includeTranscript, includeAudio, createdAt, expiresAt, revokedAt, downloadCount)

    /** The server may answer with a path relative to its host. */
    fun absoluteUrl(base: String): String = if (url.startsWith("/")) base.trimEnd('/') + url else url
}
