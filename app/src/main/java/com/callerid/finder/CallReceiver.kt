package com.callerid.finder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        // EXTRA_INCOMING_NUMBER is null for saved contacts on Android 9+
        var number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        if (number.isNullOrBlank()) {
            number = resolveFromCallLog(context)
        }

        if (number.isNullOrBlank()) {
            Log.d("CallReceiver", "Could not resolve number")
            return
        }

        val normalized = PhoneUtils.normalizeNumber(number) ?: number
        Log.d("CallReceiver", "Incoming call: $normalized")

        context.startForegroundService(
            Intent(context, CallerOverlayService::class.java).putExtra("number", normalized)
        )
    }

    private fun resolveFromCallLog(context: Context): String? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.INCOMING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "resolveFromCallLog failed: ${e.message}")
            null
        }
    }
}
