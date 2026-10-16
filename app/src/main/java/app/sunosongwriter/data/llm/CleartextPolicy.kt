package app.sunosongwriter.data.llm

import okhttp3.HttpUrl

/**
 * Decides whether plain `http://` is allowed for a URL (spec 5.1).
 *
 * Android's network security config can't hold a list the user edits at runtime, so the manifest
 * permits cleartext and this check, applied to every request, limits it to local addresses:
 * loopback, the emulator's host alias, private and link-local ranges, Tailscale's 100.64.0.0/10,
 * `.local` names, and bare host names. Everything else must use HTTPS.
 */
object CleartextPolicy {
    fun isAllowed(url: HttpUrl): Boolean = url.isHttps || isLocalHost(url.host)

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost")) return true
        if (!h.contains('.') && !h.contains(':')) return true // bare LAN / MagicDNS name
        ipv4(h)?.let { (a, b) ->
            return a == 127 || a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)
        }
        if (h.contains(':')) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        return false
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets[0] to octets[1]
    }
}
