package com.jarvis.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object WhatsAppLink {
    fun create(phoneNumber: String, message: String): String? {
        if (!phoneNumber.startsWith("+")) return null
        val internationalDigits = phoneNumber.drop(1)
        if (
            internationalDigits.length !in 8..15 ||
            internationalDigits.startsWith("0") ||
            internationalDigits.any { !it.isDigit() }
        ) {
            return null
        }

        val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "https://wa.me/$internationalDigits?text=$encodedMessage"
    }
}
