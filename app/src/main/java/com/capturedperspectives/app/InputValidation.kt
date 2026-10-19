package com.capturedperspectives.app

object InputValidation {
    private val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    fun isValidEmail(email: String) = emailPattern.matches(email.trim())
    fun credentials(email: String, password: String, name: String): String? = when {
        !isValidEmail(email) -> "Enter a valid email address."
        password.length < 6 -> "Password must contain at least 6 characters."
        name.isNotEmpty() && name.length < 2 -> "Name must contain at least 2 characters."
        else -> null
    }
    fun booking(name: String, email: String, servicePosition: Int, message: String): String? = when {
        name.length < 2 -> "Enter your name (at least 2 characters)."
        !isValidEmail(email) -> "Enter a valid email address."
        servicePosition == 0 -> "Choose a photography session type."
        message.length < 5 -> "Add a few details about your session."
        else -> null
    }
}
