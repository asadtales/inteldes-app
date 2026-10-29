package com.inteldes.app.data.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class MicKind { PHONE, BLUETOOTH, USB, WIRED }

/** One selectable microphone: the phone's own, a Bluetooth earbud/headset, a USB mic, a wired headset. */
data class MicOption(val kind: MicKind, val name: String, val device: AudioDeviceInfo) {
    /** Stable across reconnects (the numeric device id isn't), so it can be remembered as the user's choice. */
    val key: String get() = "${kind.name}|$name"

    val label: String
        get() = when (kind) {
            MicKind.PHONE -> "Mikrofon HP"
            MicKind.BLUETOOTH -> "Bluetooth · $name"
            MicKind.USB -> "USB · $name"
            MicKind.WIRED -> "Headset kabel"
        }

    val hint: String
        get() = when (kind) {
            MicKind.PHONE -> "Menangkap seluruh ruangan — pilihan terbaik untuk rapat banyak orang."
            MicKind.BLUETOOTH -> "Mic earbud: mono ±16 kHz, hanya jelas untuk suara dekat mulut."
            MicKind.USB -> "Mic USB / konferensi."
            MicKind.WIRED -> "Mic pada headset berkabel."
        }
}

/** Lists the audio inputs Android currently offers and keeps the list live as devices come and go. */
object MicSources {
    // Constants spelled out so they work on minSdk 26 (TYPE_BLE_HEADSET only exists from API 31).
    private const val TYPE_BLE_HEADSET = 26

    fun list(context: Context): List<MicOption> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { d -> kindOf(d.type)?.let { MicOption(it, d.productName?.toString().orEmpty().ifBlank { "perangkat" }, d) } }
            .distinctBy { it.key }
            .sortedBy { it.kind.ordinal }
    }

    /** Emits the current list immediately and again whenever a microphone is plugged in/out or connects over Bluetooth. */
    fun observe(context: Context): Flow<List<MicOption>> = callbackFlow {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { trySend(list(context)) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { trySend(list(context)) }
        }
        trySend(list(context))
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { manager.unregisterAudioDeviceCallback(callback) }
    }

    fun isBluetoothType(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET

    private fun kindOf(type: Int): MicKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.PHONE
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> MicKind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
        else -> null
    }

    /** Human label for a device Android reports as the active route (any type, including ones we don't offer). */
    fun describe(device: AudioDeviceInfo?): String? {
        device ?: return null
        val name = device.productName?.toString().orEmpty()
        return when (kindOf(device.type)) {
            MicKind.PHONE -> "Mikrofon HP"
            MicKind.BLUETOOTH -> "Bluetooth · ${name.ifBlank { "earbud" }}"
            MicKind.USB -> "USB · ${name.ifBlank { "mic" }}"
            MicKind.WIRED -> "Headset kabel"
            null -> name.ifBlank { null }
        }
    }
}
