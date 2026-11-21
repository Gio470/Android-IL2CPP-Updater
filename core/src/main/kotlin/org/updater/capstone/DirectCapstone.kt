package org.updater.capstone

import capstone.api.Disassembler
import capstone.api.Instruction
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Capstone reached by loading the library by name, for platforms where the stock binding cannot
 * load it at all.
 *
 * The binding that ships with the jar does not call `System.loadLibrary`: it asks JNA to find
 * `<platform>/libcapstone.so` among the classpath resources, unpack it and load the copy. Android's
 * build tools strip `.so` files out of Java resources, from the app's own and from any jar, so that
 * lookup can never succeed in an APK however the library is packaged. Loading by name is what does
 * work there, because the linker searches the app's own library directory.
 *
 * Only what the offset matcher reads is implemented; the rest of [Instruction] raises rather than
 * returning something invented.
 */
class DirectCapstone(arch: Int, mode: Int, libraryName: String = "capstone") : Disassembler {

    private val library = Native.load(libraryName, CapstoneLibrary::class.java)
    private val handle: Long
    private var closed = false

    init {
        val reference = LongByReference()
        val opened = library.cs_open(arch, mode, reference)
        if (opened != 0) {
            throw IllegalStateException("cs_open failed with error $opened")
        }
        handle = reference.value
    }

    override fun setDetail(detail: Boolean) {
        library.cs_option(handle, CS_OPT_DETAIL, NativeLong(if (detail) CS_OPT_ON else CS_OPT_OFF))
    }

    override fun disasm(code: ByteArray, address: Long): Array<Instruction> =
        disasm(code, address, 0)

    override fun disasm(code: ByteArray, address: Long, count: Long): Array<Instruction> {
        check(!closed) { "This disassembler is closed" }

        val result = PointerByReference()
        val decoded =
            library
                .cs_disasm(
                    handle,
                    code,
                    NativeLong(code.size.toLong()),
                    address,
                    NativeLong(count),
                    result,
                )
                .toLong()
                .toInt()

        if (decoded <= 0) return emptyArray()

        val pointer = result.value
        return try {
            val first = CsInsn(pointer)
            @Suppress("UNCHECKED_CAST")
            (first.toArray(decoded) as Array<CsInsn>).map { DirectInstruction(it) }.toTypedArray()
        } finally {
            library.cs_free(pointer, NativeLong(decoded.toLong()))
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            library.cs_close(LongByReference(handle))
        }
    }

    private companion object {
        const val CS_OPT_DETAIL = 2
        const val CS_OPT_OFF = 0L
        const val CS_OPT_ON = 3L
    }
}

private class DirectInstruction(source: CsInsn) : Instruction() {
    private val id = source.id
    private val address = source.address
    private val size = source.size
    private val bytes = source.bytes.copyOf(source.size.toInt().coerceIn(0, source.bytes.size))
    private val mnemonic = source.mnemonic.asText()
    private val opStr = source.opStr.asText()

    override fun getId(): Int = id

    override fun getAddress(): Long = address

    override fun getSize(): Short = size

    override fun getBytes(): ByteArray = bytes

    override fun getMnemonic(): String = mnemonic

    override fun getOpStr(): String = opStr

    override fun getOperands(): capstone.Capstone.OpInfo = unsupported("operands")

    override fun regName(register: Int): String = unsupported("register names")

    override fun mapToUnicornReg(register: Int): Int = unsupported("Unicorn register mapping")

    override fun mapToCapstoneReg(register: Int): Int = unsupported("Capstone register mapping")

    override fun regsAccess(): capstone.api.RegsAccess = unsupported("register access")

    private fun unsupported(what: String): Nothing =
        throw UnsupportedOperationException(
            "This Capstone binding decodes instructions only; $what needs the full one"
        )
}

private fun ByteArray.asText(): String {
    val end = indexOf(0.toByte()).takeIf { it >= 0 } ?: size
    return String(this, 0, end, Charsets.UTF_8)
}

/** The handful of Capstone entry points the matcher needs. */
internal interface CapstoneLibrary : Library {
    fun cs_open(arch: Int, mode: Int, handle: LongByReference): Int

    fun cs_close(handle: LongByReference): Int

    fun cs_option(handle: Long, type: Int, value: NativeLong): Int

    fun cs_disasm(
        handle: Long,
        code: ByteArray,
        codeSize: NativeLong,
        address: Long,
        count: NativeLong,
        instructions: PointerByReference,
    ): NativeLong

    fun cs_free(instructions: Pointer, count: NativeLong)
}

/**
 * `cs_insn` exactly as Capstone 4 declares it: the array is `uint8_t bytes[16]`, and getting that
 * length wrong makes every instruction after the first read from the wrong address.
 */
@Structure.FieldOrder("id", "address", "size", "bytes", "mnemonic", "opStr", "detail")
internal class CsInsn : Structure {
    @JvmField var id: Int = 0
    @JvmField var address: Long = 0
    @JvmField var size: Short = 0
    @JvmField var bytes: ByteArray = ByteArray(16)
    @JvmField var mnemonic: ByteArray = ByteArray(32)
    @JvmField var opStr: ByteArray = ByteArray(160)
    @JvmField var detail: Pointer? = null

    constructor() : super()

    constructor(pointer: Pointer) : super(pointer) {
        read()
    }
}
