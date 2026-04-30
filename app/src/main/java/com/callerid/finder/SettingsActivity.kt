package com.callerid.finder

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val vibrator by lazy { getSystemService(VIBRATOR_SERVICE) as Vibrator }
    private var lastVibrateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val switchVibrate = findViewById<Switch>(R.id.switchVibrate)
        val rowIntensity = findViewById<LinearLayout>(R.id.rowIntensity)
        val seekIntensity = findViewById<SeekBar>(R.id.seekIntensity)
        val tvIntensityValue = findViewById<TextView>(R.id.tvIntensityValue)

        val vibrateOn = prefs.getBoolean("vibrate", true)
        val intensity = prefs.getInt("vibrate_intensity", 220)

        switchVibrate.isChecked = vibrateOn
        seekIntensity.max = 255
        seekIntensity.progress = intensity
        tvIntensityValue.text = "${intensity * 100 / 255}%"
        rowIntensity.alpha = if (vibrateOn) 1f else 0.4f
        seekIntensity.isEnabled = vibrateOn

        switchVibrate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("vibrate", checked).apply()
            rowIntensity.alpha = if (checked) 1f else 0.4f
            seekIntensity.isEnabled = checked
        }

        seekIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvIntensityValue.text = "${progress * 100 / 255}%"
                prefs.edit().putInt("vibrate_intensity", progress).commit()
                val now = System.currentTimeMillis()
                if (progress > 0 && now - lastVibrateTime >= 100) {
                    lastVibrateTime = now
                    vibrator.cancel()
                    val duration = (progress * 80L / 255).coerceAtLeast(10)
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        findViewById<LinearLayout>(R.id.rowPermissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java)
                .putExtra("from_settings", true))
        }

        val tvTelegramStatus = findViewById<TextView>(R.id.tvTelegramStatus)
        tvTelegramStatus.text = if (TelegramClient.isReady()) "Logged in ✓" else "Not logged in"
        findViewById<LinearLayout>(R.id.rowTelegramLogin).setOnClickListener {
            startActivity(Intent(this, TelegramAuthActivity::class.java))
        }
    }
}
