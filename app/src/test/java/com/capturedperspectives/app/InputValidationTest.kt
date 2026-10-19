package com.capturedperspectives.app

import org.junit.Assert.*
import org.junit.Test

class InputValidationTest {
    @Test fun rejectsInvalidEmailAndShortPassword() {
        assertEquals("Enter a valid email address.", InputValidation.credentials("not-an-email", "secret1", ""))
        assertEquals("Password must contain at least 6 characters.", InputValidation.credentials("yusuf@example.com", "123", ""))
    }
    @Test fun acceptsValidLogin() { assertNull(InputValidation.credentials("yusuf@example.com", "secret1", "")) }
    @Test fun validatesBookingFields() {
        assertNotNull(InputValidation.booking("Yusuf", "bad", 1, "A portrait session"))
        assertNotNull(InputValidation.booking("Yusuf", "yusuf@example.com", 0, "A portrait session"))
        assertNull(InputValidation.booking("Yusuf", "yusuf@example.com", 2, "A portrait session"))
    }
}
