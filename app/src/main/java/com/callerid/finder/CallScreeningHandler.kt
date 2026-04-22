package com.callerid.finder

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

class CallScreeningHandler : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Allow the call through immediately — never block
        respondToCall(callDetails, CallResponse.Builder().build())

        val raw = callDetails.handle?.schemeSpecificPart
            ?: callDetails.handle?.toString()
            ?: run {
                Log.d("CallScreening", "No number found")
                return
            }

        // handle may be "tel:+91..." or just "+91..." — strip scheme if present
        val cleaned = raw.removePrefix("tel:").trim()
        val normalized = PhoneUtils.normalizeNumber(cleaned) ?: run {
            Log.d("CallScreening", "Could not normalize: $cleaned")
            return
        }

        val direction = if (callDetails.callDirection == Call.Details.DIRECTION_OUTGOING)
            "outgoing" else "incoming"
        Log.d("CallScreening", "$direction call: $normalized")

        startForegroundService(
            android.content.Intent(this, CallerOverlayService::class.java)
                .putExtra("number", normalized)
        )
    }
}
