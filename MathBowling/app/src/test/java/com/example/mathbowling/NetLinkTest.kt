package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NetLinkTest {

    @Test
    fun messagesRoundTrip() {
        val msgs = listOf(
            NetMsg.Start(1234567890123L, GameMode.RUSSIAN, Level.HARD, true),
            NetMsg.Result(3, true, 2),
            NetMsg.Done(7, 10, 65432L),
            NetMsg.Bye
        )
        for (m in msgs) assertEquals(m, NetMsg.decode(m.encode()))
        assertNull(NetMsg.decode("HELLO|from|the|future"))
        assertNull(NetMsg.decode("START|notanumber"))
    }

    private class Probe : NetLink.Listener {
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val inbox = LinkedBlockingQueue<NetMsg>()
        var isHost = false
        override fun onConnected(isHost: Boolean) { this.isHost = isHost; connected.countDown() }
        override fun onMessage(msg: NetMsg) { inbox.add(msg) }
        override fun onClosed() { closed.countDown() }
    }

    @Test
    fun twoPhonesTalkOverTcp() {
        val hostProbe = Probe()
        val guestProbe = Probe()
        val host = NetLink(hostProbe)
        val guest = NetLink(guestProbe)
        val port = host.host()
        guest.connect("127.0.0.1", port)
        assertTrue(hostProbe.connected.await(5, TimeUnit.SECONDS))
        assertTrue(guestProbe.connected.await(5, TimeUnit.SECONDS))
        assertTrue(hostProbe.isHost)
        assertTrue(!guestProbe.isHost)

        val start = NetMsg.Start(42L, GameMode.MATH, Level.MEDIUM, false)
        host.send(start)
        assertEquals(start, guestProbe.inbox.poll(5, TimeUnit.SECONDS))

        guest.send(NetMsg.Result(1, true, 1))
        guest.send(NetMsg.Done(8, 10, 1000L))
        assertEquals(NetMsg.Result(1, true, 1), hostProbe.inbox.poll(5, TimeUnit.SECONDS))
        assertEquals(NetMsg.Done(8, 10, 1000L), hostProbe.inbox.poll(5, TimeUnit.SECONDS))

        // leaving tells the other phone
        guest.close()
        assertEquals(NetMsg.Bye, hostProbe.inbox.poll(5, TimeUnit.SECONDS))
        assertTrue(hostProbe.closed.await(5, TimeUnit.SECONDS))
        host.close()
    }

    @Test
    fun sameSeedGivesBothPhonesTheSameQuestions() {
        for (english in listOf(false, true)) {
            val a = QuestionGenerator(english, Random(99))
            val b = QuestionGenerator(!english, Random(99)) // the other player may use the other language
            repeat(200) {
                val qa = a.next(Level.HARD)
                val qb = b.next(Level.HARD)
                assertEquals(qa.answer, qb.answer)
                assertEquals(qa.options, qb.options)
                assertEquals(qa.expression, qb.expression)
            }
        }
        for (lang in ForeignLanguage.values()) {
            val a = VocabQuestionGenerator(lang, false, Random(5))
            val b = VocabQuestionGenerator(lang, true, Random(5))
            repeat(200) {
                val qa = a.next(Level.MEDIUM)
                val qb = b.next(Level.MEDIUM)
                assertEquals(qa.answer, qb.answer)
                assertEquals(qa.options, qb.options)
            }
        }
        val a = AiQuestionGenerator(false, Random(8))
        val b = AiQuestionGenerator(true, Random(8))
        repeat(100) { assertEquals(a.next(Level.EASY).correctIndex, b.next(Level.EASY).correctIndex) }
    }
}
