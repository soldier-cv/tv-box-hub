package com.boxhub.http

/**
 * The 4-digit pairing code and the brute-force backoff around it.
 *
 * Split out of the Android-only `App` object on purpose: this is the one piece
 * of the auth path that can lock a legitimate user out of their own box, and it
 * has to be provable without a device.
 *
 * The rule that matters: **the backoff exists to slow down guessing, so it must
 * never touch a correct code.** The original version checked the clock first
 * and returned "unauthorized" without ever comparing the code, which meant the
 * person retyping the number shown on the TV was rejected whenever their
 * attempt landed inside the window opened by an earlier failure — and because
 * every rejection widened the window, a user who kept trying (1-2 s per
 * attempt) against a 5 s ceiling could never get in at all. The dashboard is
 * then stuck: it re-arms the gate, says the code is wrong, and the code is not
 * wrong. The gate reappearing is what a user describes as "输入配对码后页面刷新".
 *
 * So: compare first, penalise only mismatches, and let a penalty expire once it
 * has actually been served rather than ratcheting forever.
 */
class PinGuard(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** How much each additional wrong guess adds to the wait. */
    private val stepMs: Long = 400L,
    /** Ceiling for the exponential backoff. */
    private val ceilingMs: Long = 5_000L
) {

    @Volatile
    var code: String = ""
        private set

    private var failures = 0
    private var lastFailureAt = 0L
    private var lastWrong = ""

    /** Installs a freshly generated code and forgets any accumulated penalty. */
    fun reset(next: String) {
        code = next
        failures = 0
        lastFailureAt = 0L
        lastWrong = ""
    }

    /**
     * @return true when [given] is the current code. A correct code is accepted
     *   no matter how many recent failures there were.
     */
    fun allows(given: String?): Boolean {
        if (matches(given ?: "")) {
            failures = 0
            lastFailureAt = 0L
            lastWrong = ""
            return true
        }
        noteFailure(given ?: "")
        return false
    }

    /** How long the next *wrong* guess has to wait. Shown to the dashboard. */
    fun penaltyMs(): Long = minOf(ceilingMs, failures * stepMs)

    /** Fixed-length, non-short-circuiting compare so timing cannot leak the code. */
    private fun matches(given: String): Boolean {
        val expected = code
        if (given.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor given[i].code)
        return diff == 0
    }

    private fun noteFailure(wrong: String) {
        val now = clock()
        // One page load asks several questions at once (info, list, shot, ws).
        // They all carry the same wrong code, so the dashboard's own concurrency
        // must not be charged to the user as several separate guesses.
        if (wrong == lastWrong && failures > 0 && now - lastFailureAt < penaltyMs()) return
        // The previous mistake has been paid for in full — the user stopped
        // guessing for longer than the wait it imposed. Start over instead of
        // letting the penalty outlive their patience.
        if (failures > 0 && now - lastFailureAt >= penaltyMs()) failures = 0
        failures++
        lastFailureAt = now
        lastWrong = wrong
    }
}