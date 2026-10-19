package com.inteldes.app.data.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inteldes.app.IntelDesApp

/** Handles the "TANDAI" action on the recording notification — lets the user bookmark
 * the current moment without unlocking the phone or opening the app. */
const val ACTION_MARK_MOMENT = "com.inteldes.app.action.MARK_MOMENT"

class MarkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MARK_MOMENT) {
            (context.applicationContext as IntelDesApp).audioRecorder.addMark()
        }
    }
}
