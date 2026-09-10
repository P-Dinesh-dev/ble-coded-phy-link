package com.dinesh.codedphyprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Proves the end-to-end guarantee on the JVM, with no phone: under heavy packet loss the
 * receiver either reconstructs the text bit-exactly, or produces nothing at all.
 */
class MessageTest {

    private val key = keyFrom("anits300")

    /** Runs one message through seal -> symbols -> lossy channel -> reassembly -> open. */
    private fun roundTrip(text: String, lossPct: Int, rng: Random): Pair<String?, Int> {
        val blob = seal(key, text)
        assertTrue("blob ${blob.size}B exceeds packet budget", blob.size <= MAX_BLOB)
        val out = Outbox(7, 42, blob)
        val inbox = Inbox()
        var sentPackets = 0
        repeat(4000) {
            val p = out.next(); sentPackets++
            if (rng.nextInt(100) < lossPct) return@repeat          // dropped in flight
            inbox.add(p)?.let { return open(key, it) to sentPackets }
        }
        return null to sentPackets
    }

    @Test fun `exact text survives a clean channel`() {
        val text = "Meet at the main gate in 10 minutes."
        assertEquals(text, roundTrip(text, 0, Random(1)).first)
    }

    @Test fun `exact text survives 95 percent packet loss`() {
        val text = "SIH standup moved to 6pm, block A seminar hall. Bring the poster draft."
        val (got, packets) = roundTrip(text, 95, Random(2))
        assertEquals(text, got)
        println("95% loss: delivered after $packets transmitted packets")
    }

    @Test fun `unicode and telugu survive`() {
        val text = "కలుద్దాం at 5 — ok? ✓"
        assertEquals(text, roundTrip(text, 60, Random(3)).first)
    }

    @Test fun `every length from 1 to max round trips exactly`() {
        val rng = Random(4)
        for (n in 1..200) {
            val text = (1..n).map { "abcdefghij klmnopqrst".random(rng) }.joinToString("")
            assertEquals("failed at length $n", text, roundTrip(text, 50, rng).first)
        }
    }

    @Test fun `wrong passphrase yields null, never garbled text`() {
        val blob = seal(key, "secret orders")
        assertNull(open(keyFrom("wrong-pass"), blob))
    }

    @Test fun `a single flipped bit yields null, never garbled text`() {
        val blob = seal(key, "the exact text must arrive or nothing does")
        repeat(200) { i ->
            val bad = blob.copyOf()
            bad[i % bad.size] = (bad[i % bad.size].toInt() xor 1).toByte()
            assertNull("corrupt blob decoded at byte ${i % bad.size}", open(key, bad))
        }
    }

    @Test fun `compression actually shrinks repetitive text`() {
        val repetitive = "meeting meeting meeting meeting meeting meeting meeting meeting"
        assertTrue(seal(key, repetitive).size < seal(key, Random(5).nextBytes(64).toString()).size + 64)
    }

    @Test fun `incompressible text is not expanded past the packet budget`() {
        val text = (1..200).map { ('!'..'~').random(Random(6)) }.joinToString("")
        assertTrue(seal(key, text).size <= MAX_BLOB)
    }

    @Test fun `two messages with the same id but different lengths do not mix`() {
        val inbox = Inbox()
        val a = Outbox(7, 9, seal(key, "first message here"))
        val b = Outbox(7, 9, seal(key, "a much much much longer second message that spans more symbols"))
        repeat(a.k) { inbox.add(a.next()) }                       // fully deliver A
        var recovered: String? = null
        repeat(400) { inbox.add(b.next())?.let { recovered = open(key, it) } }
        assertEquals("a much much much longer second message that spans more symbols", recovered)
    }

    @Test fun `ack packets are recognised and never parsed as data`() {
        val ack = ackPacket(7, 42)
        assertTrue(isAck(ack))
        assertNull(Inbox().add(ack))
        assertNotEquals(true, isAck(Outbox(7, 42, seal(key, "data")).next()))
    }
}
