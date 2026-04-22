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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val switchVibrate = findViewById<Switch>(R.id.switchVibrate)
        val rowIntensity = findViewById<LinearLayout>(R.id.rowIntensity)
        val seekIntensity = findViewById<SeekBar>(R.id.seekIntensity)
        val tvIntensityValue = findViewById<TextView>(R.id.tvIntensityValue)

        val vibrateOn = prefs.getBoolean("vibrate", true)
        val intensity = prefs.getInt("vibrate_intensity", 128).coerceAtLeast(1)

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
                val clamped = progress.coerceAtLeast(1)
                val pct = clamped * 100 / 255
                tvIntensityValue.text = "$pct%"
                prefs.edit().putInt("vibrate_intensity", clamped).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                // Preview vibration at new intensity so user can feel it
                val clamped = sb.progress.coerceAtLeast(1)
                val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
                vib.vibrate(VibrationEffect.createOneShot(40, clamped))
            }
        })

        findViewById<LinearLayout>(R.id.rowPermissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java)
                .putExtra("from_settings", true))
        }
    }
}
