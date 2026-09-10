package com.dinesh.codedphyprobe

import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

const val HDR = 4    // sender | msgId | totalLen | esi
const val SYM = 20   // symbol bytes per packet -> 24 B manufacturer payload
const val MAX_BLOB = 255

/** PBKDF2 over a shared passphrase. Fixed salt: both phones must derive the same key with no exchange. */
fun keyFrom(pass: String): SecretKeySpec = SecretKeySpec(
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(pass.toCharArray(), "blelink".toByteArray(), 10000, 256)).encoded, "AES"
)

// Buffers are deliberately oversized: raw deflate can expand incompressible input, and a
// truncated deflate would silently corrupt the message.
private fun deflate(b: ByteArray) = Deflater(Deflater.BEST_COMPRESSION, true).run {
    setInput(b); finish()
    val out = ByteArray(b.size * 2 + 64); val n = deflate(out); val done = finished(); end()
    if (done) out.copyOf(n) else b + b   // force "not smaller" so the caller sends raw
}

private fun inflate(b: ByteArray) = Inflater(true).run {
    setInput(b)
    val out = ByteArray(8192); val n = inflate(out); end()
    out.copyOf(n)
}

/**
 * Compress (only if it actually helps), then encrypt once for the whole message.
 * Encrypt-then-fragment: one 16-byte tag per message instead of per packet.
 * Returns nonce(12) || ciphertext || tag(16).
 */
fun seal(key: SecretKeySpec, text: String): ByteArray {
    val raw = text.toByteArray()
    val z = deflate(raw)
    val body = if (z.size < raw.size) byteArrayOf(1) + z else byteArrayOf(0) + raw
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
    return nonce + c.doFinal(body)
}

/** Null on any failure — a bad GCM tag means the text is not bit-exact, so it is never shown. */
fun open(key: SecretKeySpec, blob: ByteArray): String? = runCatching {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
    val body = c.doFinal(blob, 12, blob.size - 12)
    val payload = body.copyOfRange(1, body.size)
    String(if (body[0].toInt() == 1) inflate(payload) else payload)
}.getOrNull()

/** Round-robin symbol source. v2 upgrade: replace esi>=k with XOR combinations (LT code). */
class Outbox(private val sender: Int, val msgId: Int, private val blob: ByteArray) {
    val k = (blob.size + SYM - 1) / SYM
    private var i = 0
    var acked = false

    fun next(): ByteArray {
        val esi = i++ % k
        val from = esi * SYM
        return byteArrayOf(sender.toByte(), msgId.toByte(), blob.size.toByte(), esi.toByte()) +
                blob.copyOfRange(from, minOf(from + SYM, blob.size)).copyOf(SYM)
    }
}

/** Collects symbols until a message is whole. Returns the blob exactly once, then forgets it. */
class Inbox {
    private val parts = HashMap<Int, Array<ByteArray?>>()

    fun add(p: ByteArray): ByteArray? {
        if (p.size < HDR + SYM) return null
        val sender = p[0].toInt() and 0xFF
        val msgId = p[1].toInt() and 0xFF
        val total = p[2].toInt() and 0xFF
        val esi = p[3].toInt() and 0xFF
        if (total == 0) return null                       // ACK, handled by caller
        val k = (total + SYM - 1) / SYM
        if (esi >= k) return null
        val key = sender shl 8 or msgId
        // A reused msgId with a different length means a new message — start its slots over.
        val slots = parts[key]?.takeIf { it.size == k } ?: arrayOfNulls<ByteArray>(k).also { parts[key] = it }
        slots[esi] = p.copyOfRange(HDR, HDR + SYM)
        if (slots.any { it == null }) return null
        parts.remove(key)
        return slots.requireNoNulls().reduce { a, b -> a + b }.copyOf(total)
    }

    fun forget(sender: Int, msgId: Int) { parts.remove(sender shl 8 or msgId) }
}

fun ackPacket(sender: Int, msgId: Int) = byteArrayOf(sender.toByte(), msgId.toByte(), 0, 0) + ByteArray(SYM)
fun isAck(p: ByteArray) = p.size >= HDR && p[2].toInt() == 0
