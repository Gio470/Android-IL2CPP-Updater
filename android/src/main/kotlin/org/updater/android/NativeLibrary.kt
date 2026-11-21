package org.updater.android

import android.content.Context

/**
 * Capstone reaches this app through `DirectCapstone`, which loads the library by name so the linker
 * finds it in the APK's own library directory.
 *
 * The stock binding cannot be used here: it asks JNA to find `android-aarch64/libcapstone.so` among
 * the classpath resources and unpack it, and Android's build tools strip `.so` files out of Java
 * resources, from the app's own and from any jar, so that lookup can never succeed inside an APK.
 */
object NativeLibrary {

    /** Whether the bundled Capstone loads at all, which separates a bad build from a bad call. */
    fun loadsDirectly(): Result<Unit> = runCatching { System.loadLibrary("capstone") }

    /** A failure written out in full, since the cause is usually further down the chain. */
    fun explain(context: Context, failure: Throwable): String {
        val chain =
            generateSequence(failure) { it.cause }
                .take(6)
                .joinToString(context.getString(R.string.capstone_caused_by)) {
                    "${it.javaClass.simpleName}: ${it.message}"
                }

        val direct =
            loadsDirectly()
                .fold(
                    onSuccess = { context.getString(R.string.capstone_loads) },
                    onFailure = { context.getString(R.string.capstone_fails, it.message) },
                )

        return context.getString(R.string.capstone_report, chain, direct)
    }
}
