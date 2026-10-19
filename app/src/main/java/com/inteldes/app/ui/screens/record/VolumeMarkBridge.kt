package com.inteldes.app.ui.screens.record

/**
 * Lets [MainActivity]'s key-event handling reach into whichever [RecordViewModel] is
 * currently on screen, without the Activity needing to know about Compose navigation
 * internals. Registered while `RecordScreen` is composed, cleared when it leaves —
 * volume-button presses outside that window are just ordinary volume presses.
 */
object VolumeMarkBridge {
    var onVolumeKey: (() -> Unit)? = null
}
