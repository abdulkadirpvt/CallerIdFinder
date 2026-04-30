package com.callerid.finder

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TelegramAuthActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSubmit: Button
    private lateinit var layoutInput: LinearLayout

    private var currentState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_auth)

        tvStatus = findViewById(R.id.tvAuthStatus)
        etInput = findViewById(R.id.etAuthInput)
        btnSubmit = findViewById(R.id.btnAuthSubmit)
        layoutInput = findViewById(R.id.layoutAuthInput)

        TelegramClient.onAuthStateChanged = { state ->
            runOnUiThread { updateUi(state) }
        }

        TelegramClient.init(this)

        btnSubmit.setOnClickListener {
            val value = etInput.text.toString().trim()
            if (value.isEmpty()) return@setOnClickListener
            when (currentState) {
                "AuthorizationStateWaitPhoneNumber" -> TelegramClient.submitPhone(value)
                "AuthorizationStateWaitCode"        -> TelegramClient.submitCode(value)
                "AuthorizationStateWaitPassword"    -> TelegramClient.submitPassword(value)
            }
            etInput.setText("")
        }

        // If already authorized, close immediately
        if (TelegramClient.isReady()) finish()
    }

    private fun updateUi(state: String) {
        currentState = state
        when (state) {
            "AuthorizationStateWaitPhoneNumber" -> {
                tvStatus.text = "Enter your Telegram phone number (e.g. +923001234567)"
                etInput.hint = "+92XXXXXXXXXX"
                etInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
                layoutInput.visibility = View.VISIBLE
                btnSubmit.text = "Send Code"
            }
            "AuthorizationStateWaitCode" -> {
                tvStatus.text = "Enter the SMS code sent to your phone"
                etInput.hint = "12345"
                etInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutInput.visibility = View.VISIBLE
                btnSubmit.text = "Verify"
            }
            "AuthorizationStateWaitPassword" -> {
                tvStatus.text = "Enter your 2FA password"
                etInput.hint = "Password"
                etInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                layoutInput.visibility = View.VISIBLE
                btnSubmit.text = "Submit"
            }
            "AuthorizationStateReady" -> {
                tvStatus.text = "Authorized! ✓"
                layoutInput.visibility = View.GONE
                finish()
            }
            else -> {
                tvStatus.text = "Connecting..."
                layoutInput.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        TelegramClient.onAuthStateChanged = null
        super.onDestroy()
    }
}
