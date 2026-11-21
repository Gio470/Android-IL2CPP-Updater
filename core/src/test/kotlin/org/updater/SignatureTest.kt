package org.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.updater.utils.Signature

class SignatureTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `matches concrete bytes and wildcards`() {
        val signature = Signature.parse("aa ?? cc")
        assertTrue(signature.matchesAt(bytes(0xaa, 0x00, 0xcc), 0))
        assertTrue(signature.matchesAt(bytes(0xaa, 0xff, 0xcc), 0))
        assertFalse(signature.matchesAt(bytes(0xaa, 0x00, 0xcd), 0))
    }

    @Test
    fun `a literal ff is not a wildcard`() {
        val signature = Signature.parse("aa ff")
        assertTrue(signature.matchesAt(bytes(0xaa, 0xff), 0))
        assertFalse(signature.matchesAt(bytes(0xaa, 0x01), 0))
    }

    @Test
    fun `does not match past the end of the buffer`() {
        val signature = Signature.parse("aa bb cc")
        assertFalse(signature.matchesAt(bytes(0xaa, 0xbb), 0))
        assertFalse(signature.matchesAt(bytes(0xaa, 0xbb, 0xcc), 1))
        assertFalse(signature.matchesAt(bytes(0xaa, 0xbb, 0xcc), -1))
    }

    @Test
    fun `finds every occurrence including overlapping ones`() {
        assertEquals(
            listOf(0, 4),
            Signature.parse("aa bb").findAll(bytes(0xaa, 0xbb, 0, 0, 0xaa, 0xbb)),
        )
        assertEquals(listOf(0, 1), Signature.parse("aa aa").findAll(bytes(0xaa, 0xaa, 0xaa)))
    }

    @Test
    fun `finds matches when the signature starts with a wildcard`() {
        assertEquals(listOf(1), Signature.parse("?? bb cc").findAll(bytes(0x00, 0x01, 0xbb, 0xcc)))
    }

    @Test
    fun `a signature of only wildcards is blank and matches nothing`() {
        val signature = Signature.parse("?? ?? ??")
        assertTrue(signature.isBlank)
        assertEquals(emptyList(), signature.findAll(bytes(1, 2, 3, 4)))
        assertTrue(Signature.parse("").isBlank)
    }

    @Test
    fun `startsWith compares wildcards as well as bytes`() {
        val long = Signature.parse("aa ?? cc dd")
        assertTrue(long.startsWith(Signature.parse("aa ?? cc")))
        assertFalse(long.startsWith(Signature.parse("aa bb cc")))
        assertFalse(Signature.parse("aa").startsWith(long))
        assertEquals(4, long.size)
    }
}
