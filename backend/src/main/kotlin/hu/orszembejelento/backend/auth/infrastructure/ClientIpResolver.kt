package hu.orszembejelento.backend.auth.infrastructure

import hu.orszembejelento.backend.common.config.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Determines the originating client address for rate limiting.
 *
 * ## The problem this solves
 * In production the topology is `Internet -> Caddy -> Spring Boot on 127.0.0.1`. The TCP
 * peer Spring sees is therefore always Caddy on loopback, so
 * `HttpServletRequest.getRemoteAddr()` returns `127.0.0.1` for **every** user in the world.
 * Using it directly would put the entire user base into a single rate-limit bucket: a few
 * dozen failed logins from anyone would throttle everyone, and a real attacker would be
 * indistinguishable from ordinary traffic. The per-IP limit would be worse than useless.
 *
 * ## Why the header cannot simply be trusted
 * `X-Forwarded-For` is a request header, so a client can send whatever it likes. Trusting
 * it unconditionally would let an attacker put a fresh random value on every request and
 * never exhaust any bucket, while also letting them attribute their attempts to somebody
 * else's address and get that person throttled.
 *
 * ## The rule
 * The header is consulted **only** when the immediate TCP peer is a configured trusted
 * proxy, and then only its **last** entry is used.
 *
 * The last entry is the safe one because a proxy appends the address it actually observed
 * to whatever the client sent. Given a forged header, Caddy produces
 * `<forged>, <real client>`, so everything to the left is attacker-controlled and only the
 * rightmost value was written by something we trust. Taking the leftmost entry — the usual
 * mistake, since it is the "original client" in a multi-proxy chain — would read exactly
 * the attacker-controlled part.
 *
 * The deployed Caddyfile additionally *replaces* the header rather than appending, so in
 * practice the backend sees a single value that Caddy determined. This resolver does not
 * depend on that, and stays correct either way.
 *
 * ## Trust boundary
 * The default trusted set is loopback only, which is exactly what the deployment
 * guarantees: Spring binds `127.0.0.1` and Caddy is the sole ingress. Anything already
 * able to open a loopback connection is running on the host and is inside the trust
 * boundary already; that is a documented property of the deployment, not something this
 * class can defend against.
 */
@Component
class ClientIpResolver(properties: AuthProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val trustedProxies: List<CidrRange> = properties.trustedProxies.mapNotNull(CidrRange::parseOrNull)

    init {
        if (trustedProxies.isEmpty()) {
            // Not fatal: the resolver simply falls back to the peer address, which is safe
            // but collapses everyone into one bucket behind a proxy.
            log.warn(
                "No trusted proxies configured. X-Forwarded-For will be ignored and the TCP peer " +
                    "address used instead, which behind a reverse proxy is the same for every client.",
            )
        }
    }

    fun resolve(request: HttpServletRequest): String? {
        val peer = request.remoteAddr ?: return null
        if (!isTrustedProxy(peer)) {
            // The caller reached us directly, so the peer address *is* the client and any
            // forwarded header it sent is its own invention.
            return peer
        }

        val forwarded = request.getHeader(FORWARDED_FOR_HEADER) ?: return peer

        // Rightmost entry: written by the trusted proxy, unlike anything to its left.
        val candidate = forwarded.split(',')
            .map(String::trim)
            .lastOrNull { it.isNotEmpty() }
            ?: return peer

        return normalize(candidate) ?: peer
    }

    private fun isTrustedProxy(address: String): Boolean =
        parseAddress(address)?.let { parsed -> trustedProxies.any { it.contains(parsed) } } ?: false

    /**
     * Reduces a header value to a bare address, rejecting anything unparseable.
     *
     * Validation matters: the value becomes a cache key, so an unbounded or attacker-shaped
     * string must never be stored. Anything that is not a real IP address falls back to the
     * peer.
     */
    private fun normalize(value: String): String? {
        val withoutPort = when {
            value.startsWith("[") -> value.substringAfter('[').substringBefore(']') // [::1]:1234
            value.count { it == ':' } == 1 -> value.substringBefore(':') // 1.2.3.4:5678
            else -> value
        }
        return parseAddress(withoutPort)?.hostAddress
    }

    private fun parseAddress(value: String): InetAddress? = IpLiterals.parse(value)

    private companion object {
        const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }
}

/**
 * Parses IP **literals**, never hostnames.
 *
 * The literal check comes first deliberately. `InetAddress.getByName` resolves anything
 * that is not a literal over DNS, so passing a header value straight to it would turn a
 * client-controlled string into an outbound DNS lookup on every request — a lookup the
 * attacker chooses, that blocks the request thread, and that leaks traffic to a nameserver
 * of their choosing. Rejecting non-literals up front removes that entirely; for a value
 * that *is* a literal, `getByName` performs no resolution.
 *
 * (`InetAddress.ofLiteral` would express this directly, but it needs Java 22 and the
 * toolchain is pinned to 21.)
 */
internal object IpLiterals {

    /** Strict dotted quad: rejects 999.999.999.999 and anything with stray characters. */
    private val IPV4 = Regex(
        """^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""",
    )

    /**
     * Character-level gate for IPv6. Exact structural validation is left to `getByName`,
     * which never resolves a value containing a colon.
     */
    private val IPV6_CHARS = Regex("""^[0-9A-Fa-f:.]{2,45}$""")

    fun parse(value: String): InetAddress? {
        if (value.isBlank() || !isLiteral(value)) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun isLiteral(value: String): Boolean =
        IPV4.matches(value) || (value.contains(':') && IPV6_CHARS.matches(value))
}

/** A single trusted-proxy entry: a bare address or CIDR block. */
class CidrRange private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false // never match IPv4 against IPv6

        var remaining = prefixBits
        for (index in bytes.indices) {
            if (remaining <= 0) break
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((bytes[index].toInt() and mask) != (network[index].toInt() and mask)) return false
            remaining -= 8
        }
        return true
    }

    companion object {
        fun parseOrNull(value: String): CidrRange? = runCatching {
            val trimmed = value.trim()
            val slash = trimmed.indexOf('/')
            val addressPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            val address = requireNotNull(IpLiterals.parse(addressPart)) { "not an IP literal: $addressPart" }
            val defaultBits = address.address.size * 8
            val prefix = if (slash >= 0) trimmed.substring(slash + 1).toInt() else defaultBits
            require(prefix in 0..defaultBits) { "invalid prefix length in $value" }
            CidrRange(address.address, prefix)
        }.getOrNull()
    }
}
