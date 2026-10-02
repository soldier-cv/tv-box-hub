package android.util

/**
 * Test-only stand-in for the platform Base64 helper that Ws.kt uses, so the
 * WebSocket handshake can be verified on a plain JVM.
 */
object Base64 {
    const val NO_WRAP = 2
    const val NO_PADDING = 1

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(str)
}