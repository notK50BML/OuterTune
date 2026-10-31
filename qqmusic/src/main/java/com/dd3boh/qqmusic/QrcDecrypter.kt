package com.dd3boh.qqmusic

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Opens the `<content>` of QQ Music's `lyric_download.fcg`: hex text that is QQ's own triple DES,
 * then zlib. The DES is not the standard one - two of its S-box entries differ (S2 repeats 15, S4
 * repeats 10) and the bits are read from each 32-bit half little-endian - so `javax.crypto`'s DESede
 * gives garbage; this is a straight port of a decoder that is known to read QQ's files.
 *
 * Ported from LDDC by 沉默の金 (https://github.com/chenmozhijin/LDDC),
 * `LDDC/core/decryptor/tripledes.py` and `LDDC/core/decryptor/__init__.py` (`qrc_decrypt`),
 * GPL-3.0-only - which itself follows WXRIW's QQMusicDecoder
 * (https://github.com/WXRIW/QQMusicDecoder, `DESHelper.cs` / `Decrypter.cs`, MIT).
 */
internal object QrcDecrypter {
    private val KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    private val decryptSchedule by lazy { tripleDesKeySetup(KEY, DECRYPT) }
    private val encryptSchedule by lazy { tripleDesKeySetup(KEY, ENCRYPT) }

    /** The QRC document in [hex], or an exception when it is not a whole, valid QQ payload. */
    fun decrypt(hex: String): String {
        val data = hexToBytes(hex.trim())
        require(data.isNotEmpty() && data.size % 8 == 0) { "QRC payload is not whole 8-byte blocks (${data.size} bytes)" }
        val out = ByteArray(data.size)
        for (i in data.indices step 8) {
            tripleDes(data.copyOfRange(i, i + 8), decryptSchedule).copyInto(out, i)
        }
        return inflate(out).decodeToString()
    }

    /** The other direction, for tests only: [bytes] (already zlib-compressed and padded to 8) encrypted. */
    internal fun encryptBlocks(bytes: ByteArray): ByteArray {
        require(bytes.size % 8 == 0)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices step 8) {
            tripleDes(bytes.copyOfRange(i, i + 8), encryptSchedule).copyInto(out, i)
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).also {
                require(it >= 0) { "not hex" }
            }.toByte()
        }
    }

    /** zlib, read up to the end of the stream - the zero padding to the last DES block is ignored. */
    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw IllegalStateException("QRC zlib stream is truncated")
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---- QQ's DES ----

    private val SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    /** The P permutation at the end of the round function: output bit k comes from state bit P_BOX[k]. */
    private val P_BOX = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    /** Bit [b] of [a], counted QQ's way (little-endian within each 32-bit word), moved to bit [c]. */
    private fun bitNum(a: ByteArray, b: Int, c: Int): Int =
        (((a[(b / 32) * 4 + 3 - (b % 32) / 8].toInt() and 0xff) ushr (7 - b % 8)) and 1) shl c

    private fun bitNumIntR(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitNumIntL(a: Int, b: Int, c: Int): Int = ((a shl b) and 0x80000000.toInt()) ushr c

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) shr 1) or ((a and 1) shl 4)

    private fun initialPermutation(input: ByteArray): IntArray {
        // s0 takes the odd source rows (57, 49, ... 1, then 59 ... 3, ...), s1 the even ones.
        var s0 = 0
        var s1 = 0
        var bit = 31
        for (row in intArrayOf(1, 3, 5, 7)) {
            for (k in 7 downTo 0) {
                s0 = s0 or bitNum(input, k * 8 + row, bit)
                s1 = s1 or bitNum(input, k * 8 + row - 1, bit)
                bit--
            }
        }
        return intArrayOf(s0, s1)
    }

    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        for (i in 0 until 8) {
            val base = if (i < 4) i + 4 else i - 4
            var v = 0
            for (m in 0 until 4) {
                v = v or bitNumIntR(s1, base + 8 * m, 7 - 2 * m) or bitNumIntR(s0, base + 8 * m, 6 - 2 * m)
            }
            data[i] = v.toByte()
        }
        return data
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = bitNumIntL(state, 31, 0) or ((state and 0xf0000000.toInt()) ushr 1) or bitNumIntL(state, 4, 5) or
            bitNumIntL(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitNumIntL(state, 8, 11) or
            bitNumIntL(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitNumIntL(state, 12, 17) or
            bitNumIntL(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitNumIntL(state, 16, 23)
        val t2 = bitNumIntL(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitNumIntL(state, 20, 5) or
            bitNumIntL(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitNumIntL(state, 24, 11) or
            bitNumIntL(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitNumIntL(state, 28, 17) or
            bitNumIntL(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitNumIntL(state, 0, 23)

        val l0 = ((t1 ushr 24) and 0xff) xor key[0]
        val l1 = ((t1 ushr 16) and 0xff) xor key[1]
        val l2 = ((t1 ushr 8) and 0xff) xor key[2]
        val l3 = ((t2 ushr 24) and 0xff) xor key[3]
        val l4 = ((t2 ushr 16) and 0xff) xor key[4]
        val l5 = ((t2 ushr 8) and 0xff) xor key[5]

        val s = (SBOX[0][sboxBit(l0 ushr 2)] shl 28) or
            (SBOX[1][sboxBit(((l0 and 0x03) shl 4) or (l1 ushr 4))] shl 24) or
            (SBOX[2][sboxBit(((l1 and 0x0f) shl 2) or (l2 ushr 6))] shl 20) or
            (SBOX[3][sboxBit(l2 and 0x3f)] shl 16) or
            (SBOX[4][sboxBit(l3 ushr 2)] shl 12) or
            (SBOX[5][sboxBit(((l3 and 0x03) shl 4) or (l4 ushr 4))] shl 8) or
            (SBOX[6][sboxBit(((l4 and 0x0f) shl 2) or (l5 ushr 6))] shl 4) or
            SBOX[7][sboxBit(l5 and 0x3f)]

        var out = 0
        for (k in P_BOX.indices) out = out or bitNumIntL(s, P_BOX[k], k)
        return out
    }

    private fun crypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        val (first, second) = initialPermutation(input)
        var s0 = first
        var s1 = second
        for (round in 0 until 15) {
            val previous = s1
            s1 = f(s1, key[round]) xor s0
            s0 = previous
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitNum(key, KEY_PERM_C[i], 31 - i)
            d = d or bitNum(key, KEY_PERM_D[i], 31 - i)
        }
        for (i in 0 until 16) {
            val shift = KEY_RND_SHIFT[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val row = schedule[if (mode == DECRYPT) 15 - i else i]
            for (j in 0 until 24) row[j / 8] = row[j / 8] or bitNumIntR(c, KEY_COMPRESSION[j], 7 - j % 8)
            for (j in 24 until 48) row[j / 8] = row[j / 8] or bitNumIntR(d, KEY_COMPRESSION[j] - 27, 7 - j % 8)
        }
        return schedule
    }

    private fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> {
        val k0 = key.copyOfRange(0, 8)
        val k1 = key.copyOfRange(8, 16)
        val k2 = key.copyOfRange(16, 24)
        return if (mode == ENCRYPT) {
            arrayOf(keySchedule(k0, ENCRYPT), keySchedule(k1, DECRYPT), keySchedule(k2, ENCRYPT))
        } else {
            arrayOf(keySchedule(k2, DECRYPT), keySchedule(k1, ENCRYPT), keySchedule(k0, DECRYPT))
        }
    }

    private fun tripleDes(block: ByteArray, schedule: Array<Array<IntArray>>): ByteArray {
        var data = block
        for (keys in schedule) data = crypt(data, keys)
        return data
    }
}
