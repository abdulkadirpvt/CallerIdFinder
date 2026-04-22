package com.callerid.finder

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class PermissionsActivity : AppCompatActivity() {

    private lateinit var tvPermStatus: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatusText() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateStatusText() }

    private var fromSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        fromSettings = intent.getBooleanExtra("from_settings", false)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        findViewById<Button>(R.id.btnGrant).setOnClickListener { requestNext() }
    }

    override fun onResume() {
        super.onResume()
        if (!fromSettings && allGranted()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        updateStatusText()
    }

    private fun requestNext() {
        when {
            !Settings.canDrawOverlays(this) -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            !hasPhonePermission() -> permissionLauncher.launch(arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS
            ))
            !isCallerIdRole() -> {
                val rm = getSystemService(RoleManager::class.java) as RoleManager
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        }
    }

    private fun updateStatusText() {
        tvPermStatus.text = buildString {
            append(if (Settings.canDrawOverlays(this@PermissionsActivity)) "✅" else "❌")
            append(" Display over other apps\n")
            append(if (hasPhonePermission()) "✅" else "❌")
            append(" Phone & Call permissions\n")
            append(if (isCallerIdRole()) "✅" else "❌")
            append(" Caller ID / Call Screening role")
        }
    }

    private fun allGranted() = Settings.canDrawOverlays(this) && hasPhonePermission() && isCallerIdRole()

    private fun hasPhonePermission() =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isCallerIdRole(): Boolean {
        val rm = getSystemService(RoleManager::class.java) as RoleManager
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
}
