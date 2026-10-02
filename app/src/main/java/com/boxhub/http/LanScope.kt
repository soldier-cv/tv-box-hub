package com.boxhub.http

import java.net.Inet4Address
import java.net.InetAddress

/**
 * BoxHub is a LAN-only tool: the TV box sits on the home network and the phone
 * reaches it over Wi-Fi. Binding the listening socket to 0.0.0.0 is necessary
 * for that to work, but it also means the port answers on every interface the
 * box happens to have — including a WAN-facing one on some routers.
 *
 * This predicate is the compensating control: only peers inside a private
 * address range are served. It is deliberately a pure function over
 * [InetAddress] so it can be unit-tested off-device.
 */
object LanScope {

    fun isAllowed(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true
        return if (addr is Inet4Address) privateV4(addr) else privateV6(addr)
    }

    private fun privateV4(addr: Inet4Address): Boolean {
        val b = addr.address
        return when {
            b[0] == 10.toByte() -> true                                   // 10.0.0.0/8
            b[0] == 172.toByte() && b[1].toInt() in 16..31 -> true        // 172.16.0.0/12
            b[0] == 192.toByte() && b[1] == 168.toByte() -> true         // 192.168.0.0/16
            b[0] == 169.toByte() && b[1] == 254.toByte() -> true         // 169.254.0.0/16
            b[0] == 100.toByte() && b[1].toInt() in 64..127 -> true       // 100.64.0.0/10 (CGNAT / Tailscale)
            b[0] == 127.toByte() -> true                                  // loopback
            else -> false
        }
    }

    private fun privateV6(addr: InetAddress): Boolean {
        val raw = addr.address
        if (raw.size != 16) return false
        if (raw[0] == 0xFC.toByte() || raw[0] == 0xFD.toByte()) return true   // fc00::/7 unique-local
        if (raw[0] == 0xFE.toByte() && (raw[1].toInt() and 0xC0) == 0x80) return true // fe80::/10 link-local
        // IPv4-mapped (::ffff:a.b.c.d) — judge by the embedded v4 address.
        if (raw[10].toInt() == 0xFF && raw[11].toInt() == 0xFF) {
            return privateV4(InetAddress.getByAddress(raw) as Inet4Address)
        }
        return false
    }

    /** Short, human-readable reason used in logs and the 403 body. */
    fun describe(addr: InetAddress?): String =
        if (addr == null) "unknown" else if (isAllowed(addr)) "LAN" else "non-LAN"
}