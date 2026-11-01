package com.inteldes.app.data.audio

/**
 * Which audio types can be cut where. Cutting copies the compressed samples without re-encoding, so what the
 * phone can do depends on the container it is allowed to write: the MP4 muxer takes AAC and AMR, the WebM muxer
 * takes Opus and Vorbis. Everything else (mp3, wav, flac…) cannot be cut on the phone without re-encoding; the
 * server's ffmpeg cuts mp3, wav, ogg and the formats below without re-encoding either.
 */
object AudioFormats {

    enum class Muxer { MPEG4, WEBM }

    /** What the phone writes after cutting a track of this codec, or null when it cannot cut it by itself. */
    class Output(val muxer: Muxer, val extension: String, val mimeType: String)

    /** [codec] is the track's own MIME (MediaFormat.KEY_MIME), e.g. "audio/mp4a-latm". */
    fun onDeviceOutput(codec: String): Output? = when (codec.lowercase()) {
        "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb" -> Output(Muxer.MPEG4, "m4a", "audio/mp4")
        "audio/opus", "audio/vorbis" -> Output(Muxer.WEBM, "webm", "audio/webm")
        else -> null
    }

    /** Container types the server accepts as a recording's audio, and cuts with ffmpeg (api: blobs and trim). */
    private val serverContainers = setOf("audio/mp4", "audio/aac", "audio/mpeg", "audio/wav", "audio/ogg", "audio/webm")

    /** Android and browsers use several names for the same container; the server knows one of each. */
    fun normalizeContainerMime(mime: String): String {
        val base = mime.substringBefore(';').trim().lowercase()
        return when (base) {
            "audio/m4a", "audio/x-m4a", "audio/mp4a-latm" -> "audio/mp4"
            "audio/mp3", "audio/x-mp3", "audio/x-mpeg" -> "audio/mpeg"
            "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav"
            "audio/x-aac" -> "audio/aac"
            else -> base
        }
    }

    /** True when the server can store and cut a recording of this container type. */
    fun serverCanCut(mime: String): Boolean = normalizeContainerMime(mime) in serverContainers

    /** One line per type for the user: what works on the phone and what needs the server. */
    class Row(val label: String, val extensions: String, val onPhone: Boolean, val onServer: Boolean, val note: String)

    val table: List<Row> = listOf(
        Row("M4A / MP4 (AAC)", "m4a, mp4", true, true, "Rekaman dari aplikasi ini. Dipotong di HP tanpa sandi ulang."),
        Row("AAC mentah", "aac", true, true, "Dipotong di HP."),
        Row("3GP / AMR", "3gp, amr", true, false, "Dipotong di HP; server tidak menerima jenis ini."),
        Row("WebM / Opus", "webm", true, true, "Rekaman dari browser. Dipotong di HP dan hasilnya tetap WebM."),
        Row("Ogg (Opus / Vorbis)", "ogg, opus", true, true, "Dipotong di HP, hasilnya WebM; server memotong sebagai Ogg."),
        Row("MP3", "mp3", false, true, "Hanya lewat server (perlu masuk dan sinkron web)."),
        Row("WAV", "wav", false, true, "Hanya lewat server."),
        Row("FLAC", "flac", false, false, "Belum bisa dipotong. Ubah ke M4A atau MP3 dulu."),
    )
}
