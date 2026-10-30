package com.inteldes.app.data.server

import java.net.URI

/**
 * The content of a pairing QR: `https://<server>/pair#c=<code>&k=<web_login|add_phone>`.
 *
 * Scanning only READS text; nothing in it is trusted until [parse] accepts it. The host must be exactly
 * our server — otherwise a fake QR pointing at another site could make the app send a real approval
 * (or a login) to the wrong place. A rejected QR must not cause any network request.
 */
data class PairingLink(val code: String, val kind: Kind) {
    enum class Kind(val wire: String) { WEB_LOGIN("web_login"), ADD_PHONE("add_phone") }

    companion object {
        private val CODE = Regex("^[A-Za-z0-9_-]{20,80}$")

        fun parse(raw: String, serverUrl: String): PairingLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val server = runCatching { URI(serverUrl) }.getOrNull() ?: return null
            if (uri.scheme != "https") return null                              // no http:, intent:, javascript:, …
            if (uri.userInfo != null) return null                               // https://inteldes.at-in.site@evil.com
            val host = uri.host ?: return null
            if (!host.equals(server.host, ignoreCase = true)) return null       // must be exactly our server
            if (uri.port != -1 && uri.port != 443) return null
            if (uri.path != "/pair") return null
            val fragment = uri.rawFragment ?: return null
            val params = fragment.split('&')
                .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0] to it[1] }
            val code = params["c"]?.takeIf { CODE.matches(it) } ?: return null
            val kind = Kind.entries.firstOrNull { it.wire == params["k"] } ?: return null
            return PairingLink(code, kind)
        }
    }
}
