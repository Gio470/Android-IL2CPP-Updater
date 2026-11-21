package org.updater.modern

/** A method whose new location is known, and so can help place others. */
data class ResolvedMethod(
    val name: String?,
    val sourceOffset: Int,
    val destOffset: Int,
    /** How many bytes of the old body to read when looking for outgoing calls. */
    val sourceBodyLength: Int,
)

/** A method the signature pass could not settle, with whatever candidates it left behind. */
data class PendingMethod(
    val name: String,
    val sourceOffset: Int,
    /** Destination offsets its signature matched; empty when it matched nothing. */
    val candidates: List<Int>,
    val sourceBodyLength: Int? = null,
)
