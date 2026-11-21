package org.updater

import capstone.Capstone
import com.sun.jna.Platform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.capstone.DirectCapstone

/**
 * The Android app reaches Capstone through [DirectCapstone], because the stock binding's
 * resource-unpacking loader cannot work inside an APK. That binding is hand-written down to the
 * layout of `cs_insn`, so it is checked here against the stock one: same instructions, same fields,
 * on a desktop where both can run.
 */
class DirectCapstoneTest {
    private lateinit var libraryDir: File

    /** Unpacks the desktop Capstone the jar carries, so it can be loaded by name. */
    @BeforeTest
    fun unpackLibrary() {
        libraryDir =
            File.createTempFile("capstone", "dir").let {
                it.delete()
                it.mkdirs()
                it
            }
        val resource = "/${Platform.RESOURCE_PREFIX}/libcapstone.so"
        val stream =
            javaClass.getResourceAsStream(resource)
                ?: error("No $resource in the jar; this test needs a desktop Capstone")

        stream.use { input ->
            File(libraryDir, "libcapstone.so").outputStream().use { input.copyTo(it) }
        }
        System.setProperty("jna.library.path", libraryDir.absolutePath)
    }

    @AfterTest
    fun cleanUp() {
        libraryDir.deleteRecursively()
    }

    private fun words(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, word ->
            out[i * 4] = (word and 0xFF).toByte()
            out[i * 4 + 1] = ((word shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((word shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((word shr 24) and 0xFF).toByte()
        }
        return out
    }

    // A spread of shapes: the wildcard rules key off the id and the operand text, so both matter.
    private val code =
        words(
            0xd503201fL.toInt(), // nop
            0xd65f03c0L.toInt(), // ret
            0xaa0103e0L.toInt(), // mov x0, x1
            0xd10043ffL.toInt(), // sub sp, sp, #0x10
            0x94000001L.toInt(), // bl
            0x90000008L.toInt(), // adrp x8, ...
            0xf9400108L.toInt(), // ldr x8, [x8]
            0x39000108L.toInt(), // strb w8, [x8]
            0xb4000060L.toInt(), // cbz
            0x91000400L.toInt(), // add x0, x0, #1
        )

    @Test
    fun `decodes the same instructions as the stock binding`() {
        val stock =
            Capstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }
        val direct =
            DirectCapstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }

        try {
            val expected = stock.disasm(code, 0x1000)
            val actual = direct.disasm(code, 0x1000)

            assertEquals(10, expected.size, "the stock binding should decode every instruction")
            assertEquals(expected.size, actual.size)

            expected.indices.forEach { i ->
                assertEquals(expected[i].id, actual[i].id, "id of instruction $i")
                assertEquals(expected[i].address, actual[i].address, "address of instruction $i")
                assertEquals(expected[i].size, actual[i].size, "size of instruction $i")
                assertEquals(expected[i].mnemonic, actual[i].mnemonic, "mnemonic of instruction $i")
                assertEquals(expected[i].opStr, actual[i].opStr, "operands of instruction $i")
                assertContentEquals(expected[i].bytes, actual[i].bytes, "bytes of instruction $i")
            }
        } finally {
            stock.close()
            direct.close()
        }
    }

    @Test
    fun `builds the same signature as the stock binding`() {
        val library = ByteArray(64) + code + ByteArray(64)
        val stock =
            Capstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }
        val direct =
            DirectCapstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }

        try {
            val expected = org.updater.utils.getPattern(stock, library, 64, 40)
            val actual = org.updater.utils.getPattern(direct, library, 64, 40)

            assertTrue(expected.contains("??"), "the sample should include wildcarded instructions")
            assertEquals(expected, actual)
        } finally {
            stock.close()
            direct.close()
        }
    }

    // The wildcard rules compare ids against Arm64_const. A binding whose ids sit in a different
    // space breaks every one of them without erroring, so pin the numbering down.
    @Test
    fun `instruction ids are the ones the wildcard rules compare against`() {
        val direct =
            DirectCapstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }

        try {
            val decoded = direct.disasm(code, 0).associateBy { it.mnemonic }
            assertEquals(capstone.Arm64_const.ARM64_INS_NOP, decoded.getValue("nop").id)
            assertEquals(capstone.Arm64_const.ARM64_INS_RET, decoded.getValue("ret").id)
            assertEquals(capstone.Arm64_const.ARM64_INS_BL, decoded.getValue("bl").id)
            assertEquals(capstone.Arm64_const.ARM64_INS_ADRP, decoded.getValue("adrp").id)
        } finally {
            direct.close()
        }
    }

    @Test
    fun `an empty or undecodable buffer yields nothing`() {
        val direct =
            DirectCapstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM).apply { setDetail(false) }
        try {
            assertEquals(0, direct.disasm(ByteArray(0), 0).size)
            assertEquals(0, direct.disasm(ByteArray(4), 0).size)
        } finally {
            direct.close()
        }
    }
}
