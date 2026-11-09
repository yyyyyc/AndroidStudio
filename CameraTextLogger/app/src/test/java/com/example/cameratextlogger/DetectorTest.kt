package com.example.cameratextlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {

    private fun Detector.finds(text: String) = ranges(text).isNotEmpty()

    /** What the detector finds in [text]. */
    private fun Detector.found(text: String) = ranges(text).map { text.substring(it) }

    @Test
    fun `phone numbers`() {
        assertEquals(listOf("050-1234567"), Detector.PHONE.found("Call 050-1234567 now"))
        for (phone in listOf(
            "03-6123456", "0501234567", "+972 50 123 4567", "+972-3-6123456", "*2700",
            "1-800-123-456", "+44 20 7946 0958", "(212) 555-1234", "212-555-1234",
        )) {
            assertTrue(phone, Detector.PHONE.finds(phone))
        }
        for (other in listOf("12345", "472:48-004", "2026-11-05", "+1 2")) {
            assertFalse(other, Detector.PHONE.finds(other))
        }
    }

    @Test
    fun `email addresses`() {
        assertEquals(listOf("yossi.cohen@gmail.com"), Detector.EMAIL.found("mail: yossi.cohen@gmail.com"))
        assertFalse(Detector.EMAIL.finds("yossi at gmail"))
    }

    @Test
    fun `web addresses`() {
        assertEquals(listOf("www.example.co.il"), Detector.URL.found("see www.example.co.il."))
        assertEquals(listOf("https://gov.il/abc"), Detector.URL.found("https://gov.il/abc"))
        assertEquals(listOf("ynet.co.il"), Detector.URL.found("visit ynet.co.il today"))
        assertFalse("the domain of an email", Detector.URL.finds("a@b.com"))
        assertFalse(Detector.URL.finds("version 1.5"))
    }

    @Test
    fun `dates`() {
        for (date in listOf(
            "5/11/2026", "05.11.26", "2026-11-05", "5 Nov 2026", "November 5, 2026", "Nov 2026",
            "5 בנובמבר 2026", "ה-5 לנובמבר", "נובמבר 2026",
        )) {
            assertTrue(date, Detector.DATE.finds(date))
        }
        for (other in listOf("you may go", "472.48.004", "12.345.67", "1.5 kg", "35/11/2026")) {
            assertFalse(other, Detector.DATE.finds(other))
        }
    }

    @Test
    fun `times`() {
        assertEquals(listOf("14:30"), Detector.TIME.found("Opens 14:30"))
        assertTrue(Detector.TIME.finds("9:05 pm"))
        assertFalse(Detector.TIME.finds("472:48-004"))
        assertFalse(Detector.TIME.finds("25:00"))
    }

    @Test
    fun `money`() {
        for (money in listOf("₪ 49.90", "120 ש\"ח", "120 ש״ח", "\$5", "1,200 NIS", "15 שקלים", "€3.50")) {
            assertTrue(money, Detector.MONEY.finds(money))
        }
        assertFalse(Detector.MONEY.finds("1200"))
        assertFalse(Detector.MONEY.finds("15 שקלאים"))
    }

    @Test
    fun `card numbers are checked`() {
        assertTrue(Detector.CARD.finds("4111 1111 1111 1111"))
        assertFalse("bad checksum", Detector.CARD.finds("4111 1111 1111 1112"))
    }

    @Test
    fun `IBANs are checked`() {
        assertTrue(Detector.IBAN.finds("GB82 WEST 1234 5698 7654 32"))
        assertFalse("bad checksum", Detector.IBAN.finds("GB82 WEST 1234 5698 7654 33"))
    }

    @Test
    fun `Israeli ID numbers are checked`() {
        assertTrue(Detector.ID.finds("ת.ז. 123456782"))
        assertFalse("bad check digit", Detector.ID.finds("123456789"))
        assertFalse("too long", Detector.ID.finds("1234567820"))
    }

    @Test
    fun `license plates`() {
        assertEquals(listOf("472:48-004"), Detector.PLATE.found("IL 472:48-004"))
        assertTrue(Detector.PLATE.finds("12-345-67"))
        assertTrue(Detector.PLATE.finds("123.45.678"))
        assertFalse("no separators", Detector.PLATE.finds("1234567"))
        assertFalse(Detector.PLATE.finds("472-48-0045"))
    }

    @Test
    fun `street addresses`() {
        for (address in listOf(
            "רחוב הרצל 12", "שד' רוטשילד 5א", "דרך מנחם בגין 132", "221B Baker Street", "12 Main St",
        )) {
            assertTrue(address, Detector.ADDRESS.finds(address))
        }
        assertFalse(Detector.ADDRESS.finds("הרצל"))
    }

    @Test
    fun `detectors are used in searches by name`() {
        val pattern = FindPattern("@phone|@email|hello")
        assertTrue(pattern.matches("call 050-1234567"))
        assertTrue(pattern.matches("a@b.com"))
        assertTrue(pattern.matches("HELLO"))
        assertFalse(pattern.matches("nothing here"))
        assertEquals(listOf(5..15), FindPattern("@phone").ranges("call 050-1234567"))
        assertTrue("unknown names are plain text", FindPattern("@cities").matches("x @cities"))
    }
}
