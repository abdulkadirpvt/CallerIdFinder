package com.callerid.finder

import android.content.Context

/**
 * TDLib stub — returns null until native TDLib AAR is properly integrated.
 * The app falls back to the REST API automatically.
 */
object TelegramClient {
    fun init(context: Context) {}
    fun isReady() = false
    fun submitPhone(phone: String) {}
    fun submitCode(code: String) {}
    fun submitPassword(password: String) {}
    var onAuthStateChanged: ((String) -> Unit)? = null
    suspend fun queryBot(number: String, timeoutMs: Long = 15_000L): String? = null
}
