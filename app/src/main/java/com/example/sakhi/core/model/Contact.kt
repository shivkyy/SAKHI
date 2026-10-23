package com.example.sakhi.core.model

data class Contact(
    val id: Long = 0,
    val name: String,
    val phoneNumber: String,
    val relationship: String = "",
    val priority: Int = 1, // 1 is Primary, 2..5 secondary
    val smsEnabled: Boolean = true
) {
    fun isValid(): Boolean {
        val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
        return name.isNotBlank() && cleanPhone.length >= 10
    }
}
