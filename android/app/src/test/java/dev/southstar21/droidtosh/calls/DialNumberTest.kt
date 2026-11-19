package dev.southstar21.droidtosh.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialNumberTest {
    @Test
    fun keepsDigitsAndALeadingPlusAndStripsFormatting() {
        assertEquals("+441234567890", DialNumber.clean("+44 (1234) 567-890"))
        assertEquals("112", DialNumber.clean("112"))
    }

    @Test
    fun rejectsMmiAndUssdCodes() {
        assertNull(DialNumber.clean("**21*+441234567890#"))
        assertNull(DialNumber.clean("*#06#"))
        assertNull(DialNumber.clean("123#"))
    }

    @Test
    fun rejectsEmptyOrOtherCharacters() {
        assertNull(DialNumber.clean(""))
        assertNull(DialNumber.clean("   "))
        assertNull(DialNumber.clean("12,3"))
        assertNull(DialNumber.clean("12;3"))
        assertNull(DialNumber.clean("1+2"))
        assertNull(DialNumber.clean("abc"))
    }
}
