package com.inteldes.app.navigation

sealed class Routes(val route: String) {
    data object Home : Routes("home")
    data object Record : Routes("record")
    data object Processing : Routes("processing/{recordingId}?tab={tab}") {
        // tab carries where the user tapped "COBA LAGI"/"LIHAT PROGRES" from, so finishing
        // (or re-failing) returns them to that same Detail tab instead of always Notulensi.
        fun of(id: String, tab: String = "script") = "processing/$id?tab=$tab"
    }
    // Transkrip, not Notulensi, is the default tab: picking the transcription engine
    // (on-device vs cloud) is usually the first decision a user needs to make about a
    // recording, and that choice lives on/around the Transkrip tab.
    data object Detail : Routes("detail/{recordingId}?tab={tab}") {
        fun of(id: String, tab: String = "script") = "detail/$id?tab=$tab"
    }
    data object Settings : Routes("settings")
    data object Templates : Routes("templates")
    data object Contexts : Routes("contexts")
    data object Login : Routes("login")
    data object Scan : Routes("scan/{mode}") {
        fun of(mode: String) = "scan/$mode"
    }
    data object Approve : Routes("approve/{code}") {
        fun of(code: String) = "approve/$code"
    }
    data object Claim : Routes("claim/{code}") {
        fun of(code: String) = "claim/$code"
    }
    data object ContextDetail : Routes("context/{contextId}") {
        fun of(id: String) = "context/$id"
    }
}
