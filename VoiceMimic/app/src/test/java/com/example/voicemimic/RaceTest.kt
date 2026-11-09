package com.example.voicemimic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceTest {
    @Test fun speedGrowsWithScoreAndStopsWhenSilent() {
        assertEquals(0f, RaceRules.speed(null))
        assertEquals(0f, RaceRules.speed(15))
        var prev = -1f
        for (sc in 20..100 step 10) {
            val v = RaceRules.speed(sc)
            assertTrue("score $sc", v >= prev); prev = v
        }
        // a perfect imitation crosses in FULL_SPEED_SEC, a mediocre one needs much longer
        assertEquals(1f / RaceRules.FULL_SPEED_SEC, RaceRules.speed(95), 1e-6f)
        assertTrue(1f / RaceRules.speed(50) in 20f..40f)
        assertTrue(1f / RaceRules.speed(70) < RaceLevel.HARD.sec)
    }

    @Test fun networkOutcome() {
        assertEquals(RaceOutcome.WIN, RaceRules.outcome(1f, 9000, 0.6f, null))
        assertEquals(RaceOutcome.LOSE, RaceRules.outcome(0.8f, null, 1f, 12000))
        assertEquals(RaceOutcome.LOSE, RaceRules.outcome(1f, 12500, 1f, 12000))
        assertEquals(RaceOutcome.WIN, RaceRules.outcome(0.7f, null, 0.5f, null))
        assertEquals(RaceOutcome.TIE, RaceRules.outcome(0.5f, null, 0.5f, null))
    }

    @Test fun messagesRoundTrip() {
        val wav = ByteArray(5000) { (it * 7).toByte() }
        val v = RaceMsg.decode(RaceMsg.Voice("Zira | test", 40, wav).encode()) as RaceMsg.Voice
        assertEquals("Zira / test", v.name); assertEquals(40, v.limitSec); assertArrayEquals(wav, v.wav)
        assertEquals(RaceMsg.Pos(0.4321f, 57), RaceMsg.decode(RaceMsg.Pos(0.4321f, 57).encode()))
        assertEquals(RaceMsg.Finish(12345), RaceMsg.decode(RaceMsg.Finish(12345).encode()))
        assertEquals(RaceMsg.Go, RaceMsg.decode("GO"))
        assertEquals(RaceMsg.Hello(1, "Pixel 8"), RaceMsg.decode(RaceMsg.Hello(1, "Pixel 8").encode()))
        assertNull(RaceMsg.decode("SOMETHING|new"))
        assertNull(RaceMsg.decode("POS|abc"))
    }
}
