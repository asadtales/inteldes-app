package com.inteldes.app.navigation

sealed class Routes(val route: String) {
    data object Home : Routes("home")
    data object Record : Routes("record")
    data object Processing : Routes("processing/{recordingId}") {
        fun of(id: String) = "processing/$id"
    }
    data object Detail : Routes("detail/{recordingId}?tab={tab}") {
        fun of(id: String, tab: String = "notes") = "detail/$id?tab=$tab"
    }
    data object Settings : Routes("settings")
}
