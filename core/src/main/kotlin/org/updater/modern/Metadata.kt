package org.updater.modern

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Method names and addresses from an Il2CppDumper `script.json`.
 *
 * Optional throughout. When present it does two things: bounds a signature so it cannot run past
 * the end of a method into the next one, and, paired with a dump of the new library, says outright
 * where a method went when the name and signature identify it uniquely.
 *
 * Ported from `src/binary/metadata.ts`.
 */
class Metadata private constructor(private val methods: List<DumpMethod>) {

    data class DumpMethod(
        @SerializedName("Name") val name: String = "",
        @SerializedName("Address") val address: Long = 0,
        @SerializedName("Signature") val signature: String = "",
    )

    private data class Script(@SerializedName("ScriptMethod") val methods: List<DumpMethod>?)

    private val byName: Map<String, List<DumpMethod>> = methods.groupBy { it.name }

    /** Every distinct address, ascending, for finding where a method must end. */
    private val addresses: List<Long> =
        methods.map { it.address }.filter { it > 0 }.distinct().sorted()

    fun identify(name: String, signature: String? = null): List<DumpMethod> {
        val found = byName[name] ?: return emptyList()
        return if (signature == null) found else found.filter { it.signature == signature }
    }

    /** The next method's address, which is where this one has to stop. */
    fun nextAddress(address: Long): Long? {
        var low = 0
        var high = addresses.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (addresses[middle] <= address) low = middle + 1 else high = middle
        }
        return addresses.getOrNull(low)
    }

    companion object {
        /** Null rather than an exception: a dump is an optional extra, not a requirement. */
        fun parse(json: String): Metadata? {
            val script =
                try {
                    Gson().fromJson(json, Script::class.java)
                } catch (e: Exception) {
                    return null
                }

            val methods =
                script?.methods?.filter { it.name.isNotEmpty() && it.address >= 0 } ?: return null

            return if (methods.isEmpty()) null else Metadata(methods)
        }
    }
}
