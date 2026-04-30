package com.callerid.finder

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvNumber: EditText
    private lateinit var tvSearchResult: TextView
    private lateinit var contactList: RecyclerView
    private var searchJob: Job? = null

    private data class Contact(val name: String, val number: String)
    private val allContacts = mutableListOf<Contact>()

    // digit, letters, frameId
    private val keys = listOf(
        Triple("1", "QO",   R.id.key1),
        Triple("2", "ABC",  R.id.key2),
        Triple("3", "DEF",  R.id.key3),
        Triple("4", "GHI",  R.id.key4),
        Triple("5", "JKL",  R.id.key5),
        Triple("6", "MNO",  R.id.key6),
        Triple("7", "PQRS", R.id.key7),
        Triple("8", "TUV",  R.id.key8),
        Triple("9", "WXYZ", R.id.key9),
        Triple("*", "",     R.id.keyStar),
        Triple("0", "+",    R.id.key0),
        Triple("#", "",     R.id.keyHash)
    )

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val isVibrateEnabled get() = prefs.getBoolean("vibrate", true)
    private val vibrator by lazy { getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // Get contact ID from the contact URI
        val contactId = contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@registerForActivityResult

        // Query ALL phone numbers for this contact
        val numbers = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { c ->
            while (c.moveToNext()) {
                val num = c.getString(0)?.filter { it.isDigit() || it == '+' } ?: continue
                if (num.isNotBlank()) numbers.add(num)
            }
        }

        when {
            numbers.isEmpty() -> return@registerForActivityResult
            numbers.size == 1 -> setNumber(numbers[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Select number")
                .setItems(numbers.toTypedArray()) { _, i -> setNumber(numbers[i]) }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvNumber = findViewById(R.id.tvNumber)
        tvSearchResult = findViewById(R.id.tvSearchResult)
        contactList = findViewById(R.id.contactList)
        contactList.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            loadContacts()
        }

        tvNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterContacts(s?.toString() ?: "")
            }
        })

        // Suppress keyboard on focus but allow copy/paste context menu
        try {
            val m = EditText::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(tvNumber, false)
        } catch (_: Exception) {}

        // On touch: hide keyboard but let the event pass through for selection/copy-paste
        tvNumber.setOnTouchListener { v, event ->
            hideKeyboard()
            v.onTouchEvent(event)
            false
        }

        tvNumber.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) hideKeyboard()
        }

        setupKeypad()
        setupActionButtons()
    }

    override fun onPause() {
        super.onPause()
        tvNumber.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!allPermissionsGranted()) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }
        tvNumber.post { hideKeyboard() }
    }

    private fun setupKeypad() {
        val inflater = LayoutInflater.from(this)
        keys.forEach { (digit, letters, frameId) ->
            val frame = findViewById<FrameLayout>(frameId)
            val view = inflater.inflate(R.layout.dialer_key, frame, false)
            view.findViewById<TextView>(R.id.tvDigit).text = digit
            view.findViewById<TextView>(R.id.tvLetters).text = letters
            frame.addView(view)
            frame.setOnClickListener {
                vibrate()
                val t = tvNumber.text.toString()
                val cursor = tvNumber.selectionStart.coerceIn(0, t.length)
                val new = t.substring(0, cursor) + digit + t.substring(cursor)
                tvNumber.setText(new)
                tvNumber.setSelection(cursor + digit.length)
                tvNumber.requestFocus()
            }
        }
    }

    private fun setupActionButtons() {
        findViewById<ImageView>(R.id.btnCall).setOnClickListener {
            val number = tvNumber.text.toString().trim()
            if (number.isNotEmpty()) {
                try {
                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                } catch (e: Exception) {
                    showResult("Error making call: ${e.message}")
                }
            } else {
                val lastNumber = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER),
                    null, null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (lastNumber != null) setNumber(lastNumber)
            }
        }

        val backspace = findViewById<TextView>(R.id.btnBackspace)
        var isLongPress = false

        backspace.setOnClickListener {
            if (isLongPress) { isLongPress = false; return@setOnClickListener }
            vibrate()
            searchJob?.cancel()
            searchJob = null
            val t = tvNumber.text.toString()
            val cursor = tvNumber.selectionStart.coerceIn(0, t.length)
            if (cursor > 0) {
                val new = t.removeRange(cursor - 1, cursor)
                tvNumber.setText(new)
                tvNumber.setSelection(cursor - 1)
            }
            clearResult()
        }

        backspace.setOnLongClickListener {
            isLongPress = true
            vibrate()
            searchJob?.cancel()
            searchJob = null
            tvNumber.setText("")
            clearResult()
            true
        }

        backspace.setOnTouchListener { v, event ->
            v.onTouchEvent(event)
            true
        }

        findViewById<ImageView>(R.id.btnSearchId).setOnClickListener {
            val number = tvNumber.text.toString().trim()
            if (number.isNotEmpty()) {
                contactList.visibility = View.GONE
                searchNumber(number)
            }
            else showResult("Please enter a number")
        }

        findViewById<LinearLayout>(R.id.btnRecents).setOnClickListener { showRecents() }
        findViewById<LinearLayout>(R.id.btnContacts).setOnClickListener {
            contactPickerLauncher.launch(null)
        }

        findViewById<ImageView>(R.id.btnMore).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "Settings")
            popup.menu.add(0, 2, 1, "About")
            popup.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> startActivity(Intent(this, AboutActivity::class.java))
                }
                true
            }
            popup.show()
        }
    }

    private fun setNumber(number: String) {
        val clean = PhoneUtils.normalizeNumber(number) ?: number.filter { it.isDigit() }
        tvNumber.setText(clean)
        tvNumber.setSelection(clean.length)
        clearResult()
    }

    private data class CallEntry(val number: String, val name: String, val type: Int, val date: Long)
    private sealed class RecentItem {
        data class Header(val label: String) : RecentItem()
        data class Call(val entry: CallEntry) : RecentItem()
    }

    private fun showRecents() {
        val entries = mutableListOf<CallEntry>()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.size < 100) {
                    val num = cursor.getString(0) ?: continue
                    entries.add(CallEntry(
                        number = num,
                        name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: num,
                        type = cursor.getInt(2),
                        date = cursor.getLong(3)
                    ))
                }
            }
        } catch (_: Exception) {}

        if (entries.isEmpty()) { showResult("No recent calls found"); return }

        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

        fun dateLabel(ms: Long): String {
            val cal = Calendar.getInstance().apply { timeInMillis = ms }
            return when {
                cal >= today -> "Today"
                cal >= yesterday -> "Yesterday"
                else -> dateFmt.format(Date(ms))
            }
        }

        val items = mutableListOf<RecentItem>()
        var lastLabel = ""
        entries.forEach { e ->
            val label = dateLabel(e.date)
            if (label != lastLabel) { items.add(RecentItem.Header(label)); lastLabel = label }
            items.add(RecentItem.Call(e))
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_recents, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvRecents)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this).setView(view).setNegativeButton("Close", null).create()

        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)
            inner class CallVH(v: View) : RecyclerView.ViewHolder(v) {
                val icon: TextView = v.findViewById(R.id.tvCallIcon)
                val name: TextView = v.findViewById(R.id.tvName)
                val time: TextView = v.findViewById(R.id.tvTime)
            }
            override fun getItemViewType(pos: Int) = if (items[pos] is RecentItem.Header) 0 else 1
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                val inf = LayoutInflater.from(parent.context)
                return if (type == 0) HeaderVH(inf.inflate(R.layout.item_recent_header, parent, false))
                       else CallVH(inf.inflate(R.layout.item_recent_call, parent, false))
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                when (val item = items[pos]) {
                    is RecentItem.Header -> (holder as HeaderVH).itemView.findViewById<TextView>(R.id.tvHeader).text = item.label
                    is RecentItem.Call -> {
                        val h = holder as CallVH
                        val (icon, color) = when (item.entry.type) {
                            CallLog.Calls.OUTGOING_TYPE -> "↗" to 0xFF4CAF50.toInt()
                            CallLog.Calls.MISSED_TYPE   -> "↙" to 0xFFE53935.toInt()
                            else                        -> "↙" to 0xFF4CAF50.toInt()
                        }
                        h.icon.text = icon
                        h.icon.setTextColor(color)
                        h.name.text = item.entry.name
                        h.time.text = timeFmt.format(Date(item.entry.date))
                        h.itemView.setOnClickListener {
                            setNumber(item.entry.number)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun searchNumber(number: String) {
        val normalized = PhoneUtils.normalizeNumber(number)
        if (normalized == null) {
            showResult("Please enter a valid 10-digit number")
            return
        }
        searchJob?.cancel()
        showResult("Searching...")
        searchJob = lifecycleScope.launch {
            var result: String? = null
            for (attempt in 1..10) {
                showResult("Searching ($attempt/10)...")
                result = withContext(Dispatchers.IO) {
                    runCatching {
                        val response = URL("https://database-sigma-nine.vercel.app/number/$normalized?api_key=YOUR-PASSWORD").readText()
                        if (response.contains("API Error", ignoreCase = true)) throw Exception("API Error")
                        parseResponse(response)
                    }.getOrNull()
                }
                if (result != null) break
                delay(2000)
            }
            showResult(result ?: "No info found")
        }
    }

    private fun showResult(text: String) {
        tvSearchResult.visibility = View.VISIBLE
        tvSearchResult.text = text
    }

    private fun clearResult() {
        tvSearchResult.visibility = View.GONE
        tvSearchResult.text = ""
        contactList.visibility = View.GONE
    }

    private fun parseResponse(raw: String): String {
        val jsonStart = raw.indexOf('{')
        if (jsonStart == -1) return "No info found"
        return try {
            val obj = JSONObject(raw.substring(jsonStart))
            val r = obj.optJSONArray("results")?.optJSONObject(0) ?: obj
            val address = r.optString("address").ifBlank { obj.optString("address") }
            buildString {
                val name = r.optString("name")
                val fname = r.optString("fname")
                val id = r.optString("id")
                val alt = r.optString("alt")
                if (name.isNotBlank()) append("👤 $name")
                if (fname.isNotBlank()) append(" (S/O $fname)")
                if (alt.isNotBlank()) append("\n📱 $alt")
                if (id.isNotBlank()) append("\n🆔 $id")
                if (address.isNotBlank()) append("\n📍 $address")
                if (isEmpty()) append("No info found")
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun loadContacts() {
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim() ?: continue
                val num = c.getString(1)?.filter { it.isDigit() || it == '+' } ?: continue
                if (name.isNotBlank() && num.isNotBlank())
                    allContacts.add(Contact(name, num))
            }
        }
    }

    private fun toT9(name: String): String {
        val map = mapOf(
            'a' to '2', 'b' to '2', 'c' to '2',
            'd' to '3', 'e' to '3', 'f' to '3',
            'g' to '4', 'h' to '4', 'i' to '4',
            'j' to '5', 'k' to '5', 'l' to '5',
            'm' to '6', 'n' to '6', 'o' to '6',
            'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
            't' to '8', 'u' to '8', 'v' to '8',
            'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
        )
        return name.lowercase().map { map[it] ?: it }.joinToString("")
    }

    private fun filterContacts(query: String) {
        if (query.isEmpty()) {
            contactList.visibility = View.GONE
            return
        }
        val digits = query.filter { it.isDigit() }
        if (digits.isEmpty()) {
            contactList.visibility = View.GONE
            return
        }
        val matches = allContacts.filter { c ->
            c.number.contains(digits) || toT9(c.name).contains(digits)
        }.take(10)
        if (matches.isEmpty()) {
            contactList.visibility = View.GONE
            return
        }
        contactList.visibility = View.VISIBLE
        contactList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val name: TextView = v.findViewById(R.id.tvContactName)
                val number: TextView = v.findViewById(R.id.tvContactNumber)
            }
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                VH(LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false))
            override fun getItemCount() = matches.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val h = holder as VH
                val c = matches[pos]
                h.name.text = c.name
                h.number.text = c.number
                h.itemView.setOnClickListener { setNumber(c.number) }
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val rm = getSystemService(RoleManager::class.java) as RoleManager
        return Settings.canDrawOverlays(this) &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun vibrate() {
        if (!isVibrateEnabled) return
        val intensity = getSharedPreferences("settings", MODE_PRIVATE).getInt("vibrate_intensity", 220)
        if (intensity <= 0) return
        val duration = (intensity * 80L / 255).coerceAtLeast(10)
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(tvNumber.windowToken, 0)
    }
}
