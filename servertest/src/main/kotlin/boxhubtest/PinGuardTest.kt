package boxhubtest

import com.boxhub.http.PinGuard

/**
 * Pairing-code guard tests.
 *
 * The bug these exist for: the backoff used to be evaluated *before* the code
 * was compared, so a correct code was rejected whenever it arrived inside the
 * window left by an earlier failure. Since the dashboard fires four requests at
 * once (info, list, shot, ws) and each rejection widened the window, a user who
 * retyped the code every second or two against the 5 s ceiling could never get
 * in — the gate just kept reappearing, which is what gets reported as
 * "输入配对码后页面刷新，进不去".
 *
 * A fake clock is injected so the time-dependent parts are exact rather than
 * "sleep and hope".
 */
private class FakeClock(var now: Long = 1_000_000L) {
    fun advance(ms: Long) { now += ms }
}

fun pinGuardTests() {
    println("\n[20] pairing code: the correct code is never blocked by the backoff")

    val clock = FakeClock()
    val guard = PinGuard(clock = { clock.now })
    guard.reset("4821")

    check("correct code accepted", guard.allows("4821"))
    check("wrong code rejected", !guard.allows("1111"))
    check("empty key rejected", !guard.allows(null))
    check("wrong length rejected", !guard.allows("482"))
    check("right digits, wrong order, rejected", !guard.allows("1428"))

    // The regression: the moment that matters is immediately after a failure.
    guard.reset("4821")
    !guard.allows("9999")
    check("backoff is armed after a wrong guess", guard.penaltyMs() > 0L, "${guard.penaltyMs()}ms")
    check(
        "the correct code is accepted inside the backoff window",
        guard.allows("4821"),
        "penalty was ${guard.penaltyMs()}ms"
    )
    check("a success clears the penalty", guard.penaltyMs() == 0L, "${guard.penaltyMs()}ms")

    // ...and it stays acceptable no matter how ugly the history is.
    guard.reset("4821")
    repeat(40) { guard.allows("0000") }
    check("correct code accepted after 40 failures", guard.allows("4821"))
    check("backoff never exceeds its ceiling", guard.penaltyMs() <= 5_000L, "${guard.penaltyMs()}ms")

    println("\n[21] pairing code: a page-load burst is one mistake, not four")

    val burst = FakeClock()
    val g2 = PinGuard(clock = { burst.now })
    g2.reset("4821")
    // The dashboard opens with four parallel authenticated requests carrying the
    // same stale code: info, list, screenshot and the socket.
    repeat(4) {
        burst.advance(5)
        g2.allows("0000")
    }
    check("four parallel wrong requests cost one guess", g2.penaltyMs() == 400L, "${g2.penaltyMs()}ms")
    check("the correct code works straight afterwards", g2.allows("4821"))

    println("\n[22] pairing code: guessing still gets slower")

    val walk = FakeClock()
    val g3 = PinGuard(clock = { walk.now })
    g3.reset("4821")
    val penalties = ArrayList<Long>()
    repeat(6) { i ->
        walk.advance(10)          // a brute forcer hammering the port
        g3.allows(String.format("%04d", i))
        penalties.add(g3.penaltyMs())
    }
    check(
        "each new wrong guess lengthens the wait",
        penalties == listOf(400L, 800L, 1200L, 1600L, 2000L, 2400L),
        penalties.toString()
    )

    val ceiling = FakeClock()
    val g4 = PinGuard(clock = { ceiling.now })
    g4.reset("4821")
    repeat(40) {
        ceiling.advance(1)
        g4.allows("7777" + it)   // distinct wrong codes, no pause
    }
    check("penalty is capped at 5s", g4.penaltyMs() == 5_000L, "${g4.penaltyMs()}ms")

    println("\n[23] pairing code: a served penalty expires instead of ratcheting")

    val decay = FakeClock()
    val g5 = PinGuard(clock = { decay.now })
    g5.reset("4821")
    g5.allows("0000")
    g5.allows("0001")
    check("two guesses in a row escalate", g5.penaltyMs() == 800L, "${g5.penaltyMs()}ms")
    decay.advance(800)             // the guesser waited as instructed
    g5.allows("0002")
    check("a guess after waiting starts a fresh penalty", g5.penaltyMs() == 400L, "${g5.penaltyMs()}ms")

    println("\n[24] pairing code: rotating the code clears everything")

    val rot = FakeClock()
    val g6 = PinGuard(clock = { rot.now })
    g6.reset("4821")
    repeat(10) { g6.allows("0000") }
    check("penalty present before rotation", g6.penaltyMs() > 0L, "${g6.penaltyMs()}ms")
    g6.reset("7777")
    check("the old code no longer works", !g6.allows("4821"))
    check("the new code works immediately", g6.allows("7777"))
    check("rotation cleared the penalty", g6.penaltyMs() == 0L, "${g6.penaltyMs()}ms")

    println("\n[25] the user loop from the bug report, end to end")
    run {
        // Model exactly what a person does: read the code off the TV, type it,
        // get "wrong code", retype. 1.2 s per attempt.
        val t = FakeClock()
        val g = PinGuard(clock = { t.now })
        g.reset("4821")
        var attempt = 0
        var accepted = false
        while (attempt < 25 && !accepted) {
            attempt++
            t.advance(1_200)                    // typing four digits
            if (attempt == 1) {
                g.allows("9999")                // one genuine typo
            } else if (attempt == 2) {
                g.allows("")                    // the page-load burst, twice
                g.allows("")
            }
            accepted = g.allows("4821")         // the code that is on the TV
        }
        check(
            "the correct code is accepted on attempt $attempt (old logic: never)",
            accepted,
            "penalty ${g.penaltyMs()}ms after $attempt attempts"
        )
    }

    println("\n[26] the code on the TV is the code the server accepts")
    run {
        // The regression from the real device: `App.pin` cached its own copy of
        // the code, and start() rotated that copy without the guard hearing
        // about it — so the screen showed one number and the server compared
        // against another, and every pairing was refused. App.pin is now a
        // read-only view over the guard; this asserts the invariant that makes
        // that shape safe: whatever the guard exposes is what it accepts.
        val g = PinGuard(clock = { 0L })
        var accepted = 0
        repeat(50) {
            g.reset(String.format("%04d", it + 1))
            if (g.allows(g.code)) accepted++
        }
        check("every rotated code is accepted immediately", accepted == 50, "$accepted/50")
        check("the previous code stops working after a rotation", !g.allows("0001"))
        check("the guard exposes a full-length code", g.code.length == 4, g.code)
    }
}