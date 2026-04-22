package com.callerid.finder

object PhoneUtils {
    /** Strips +91 / 0091 / 91 / leading 0 prefix and returns a 10-digit number, or null if invalid. */
    fun normalizeNumber(raw: String): String? {
        var n = raw.filter { it.isDigit() }
        when {
            n.startsWith("0091")            -> n = n.removePrefix("0091")
            n.startsWith("91") && n.length == 12 -> n = n.removePrefix("91")
            n.startsWith("0") && n.length == 11  -> n = n.removePrefix("0")
        }
        return if (n.length == 10) n else null
    }
}
