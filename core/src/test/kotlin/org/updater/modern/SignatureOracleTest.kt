package org.updater.modern

import com.google.gson.Gson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.createArm64Disassembler

/**
 * Checks signature building against the TypeScript updater over a real AArch64 library.
 *
 * `signature-oracle.json` holds what that implementation produced for the same library and the same
 * offsets. Three things have to line up: the disassembly itself, the signatures grown from it, and
 * the matches those signatures find. If our Capstone disagreed with theirs about an operand, every
 * pattern downstream would differ, so the instruction comparison comes first.
 */
class SignatureOracleTest {

    private data class Insn(
        val address: Long,
        val bytes: String,
        val mnemonic: String,
        val opStr: String,
    )

    private data class Sig(
        val offset: Int,
        val pattern: String,
        val length: Int,
        val matches: List<Int>,
    )

    private data class ScanCase(val pattern: String, val matches: List<Int>)

    private data class Oracle(
        val instructions: List<Insn>,
        val signatures: List<Sig>,
        val scans: List<ScanCase>,
    )

    // The AArch64 Capstone the app ships: a real library, and one this repository already carries.
    private val libraryFile =
        File("../android/src/main/jniLibs/arm64-v8a/libcapstone.so").absoluteFile

    private val library: ByteArray by lazy {
        assertTrue(libraryFile.isFile, "the oracle needs ${libraryFile.path}")
        libraryFile.readBytes()
    }

    private val oracle: Oracle by lazy {
        val json =
            javaClass.getResourceAsStream("/signature-oracle.json")?.bufferedReader()?.readText()
                ?: error("signature-oracle.json is missing from the test resources")
        Gson().fromJson(json, Oracle::class.java)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `our Capstone decodes what theirs decoded`() {
        val cs = createArm64Disassembler()
        try {
            val start = oracle.instructions.first().address.toInt()
            val decoded = cs.disasm(library.copyOfRange(start, start + 4096), start.toLong())

            assertEquals(oracle.instructions.size, decoded.size, "instruction count")

            val differences =
                oracle.instructions.indices
                    .mapNotNull { i ->
                        val expected = oracle.instructions[i]
                        val actual = decoded[i]
                        val same =
                            expected.address == actual.address &&
                                expected.bytes == hex(actual.bytes) &&
                                expected.mnemonic == actual.mnemonic &&
                                expected.opStr == actual.opStr
                        if (same) null
                        else
                            "at 0x${expected.address.toString(16)}: expected " +
                                "'${expected.mnemonic} ${expected.opStr}', got " +
                                "'${actual.mnemonic} ${actual.opStr}'"
                    }
                    .take(5)

            assertEquals(emptyList(), differences)
        } finally {
            cs.close()
        }
    }

    @Test
    fun `signatures grow exactly as the original grows them`() {
        val cs = createArm64Disassembler()
        try {
            val differences =
                oracle.signatures
                    .mapNotNull { expected ->
                        val actual =
                            Signature.unique(cs, library, library, expected.offset, maxLength = 300)
                        when {
                            actual.pattern != expected.pattern ->
                                "0x${expected.offset.toString(16)}: pattern differs"
                            actual.length != expected.length ->
                                "0x${expected.offset.toString(16)}: length ${actual.length} != ${expected.length}"
                            actual.matches != expected.matches ->
                                "0x${expected.offset.toString(16)}: matches ${actual.matches} != ${expected.matches}"
                            else -> null
                        }
                    }
                    .take(5)

            assertEquals(emptyList(), differences)
        } finally {
            cs.close()
        }
    }

    @Test
    fun `the matcher finds what the original finds`() {
        val differences =
            oracle.scans
                .mapNotNull { case ->
                    val actual = Scan.scan(case.pattern, library, restrictToCode = false)
                    if (actual == case.matches) null else "expected ${case.matches}, got $actual"
                }
                .take(5)

        assertEquals(emptyList(), differences)
    }

    @Test
    fun `the oracle exercises growth rather than one easy case`() {
        val lengths = oracle.signatures.map { it.length }.distinct()
        assertTrue(lengths.size > 5, "expected varied signature lengths, got $lengths")
        assertTrue(lengths.any { it > 48 }, "expected some signatures to have grown")
        assertTrue(
            oracle.signatures.any { it.matches.size == 1 },
            "expected some signatures to be unique",
        )
    }
}
