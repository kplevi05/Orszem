package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.auth.infrastructure.ClientIpResolver
import hu.orszembejelento.backend.common.config.AuthProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * The rules that keep per-IP rate limiting meaningful behind a reverse proxy.
 *
 * Two failure modes are being guarded against, and they pull in opposite directions:
 * ignoring `X-Forwarded-For` entirely would put every production user in one bucket, while
 * trusting it blindly would let any client mint unlimited buckets and frame other people's
 * addresses. Only a header written by a trusted peer counts.
 */
class ClientIpResolverTest {

    private val resolver = ClientIpResolver(AuthProperties())

    private fun request(peer: String, forwardedFor: String? = null) =
        MockHttpServletRequest().apply {
            remoteAddr = peer
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }

    // ------------------------------------------------------ trusted proxy (production)

    @Test
    fun `behind a trusted proxy the forwarded address is used`() {
        val resolved = resolver.resolve(request(peer = "127.0.0.1", forwardedFor = "203.0.113.7"))
        check(resolved == "203.0.113.7") {
            "the client behind the proxy must be identified, got $resolved"
        }
    }

    @Test
    fun `different clients behind the same proxy resolve differently`() {
        // The whole point: without this, every user shares the loopback bucket.
        val first = resolver.resolve(request("127.0.0.1", "203.0.113.7"))
        val second = resolver.resolve(request("127.0.0.1", "198.51.100.4"))

        check(first != second) { "distinct clients must not share a rate-limit key" }
        check(first == "203.0.113.7" && second == "198.51.100.4")
    }

    @Test
    fun `only the rightmost entry is believed`() {
        // A proxy appends what it observed, so given a forged header the real client is the
        // last element and everything before it is attacker-controlled. Taking the leftmost
        // value - the intuitive "original client" - would read exactly the forged part.
        val resolved = resolver.resolve(
            request("127.0.0.1", forwardedFor = "203.0.113.7, 198.51.100.4"),
        )
        check(resolved == "198.51.100.4") {
            "the rightmost entry is the one a trusted proxy wrote, got $resolved"
        }
    }

    @Test
    fun `a forged header cannot impersonate another client through the leftmost slot`() {
        val victim = "203.0.113.7"
        val attacker = "198.51.100.9"

        // The attacker sends "victim" hoping to get the victim throttled; the proxy appends
        // the attacker's real address.
        val resolved = resolver.resolve(request("127.0.0.1", "$victim, $attacker"))

        check(resolved == attacker) { "the attempt must be attributed to the attacker" }
        check(resolved != victim) { "an attacker must not be able to frame another address" }
    }

    // ------------------------------------------------- untrusted peer (direct connection)

    @Test
    fun `a direct caller cannot choose its own address`() {
        // Not loopback, so this connection did not come through the trusted proxy and its
        // header is its own invention.
        val resolved = resolver.resolve(request(peer = "203.0.113.50", forwardedFor = "10.0.0.1"))

        check(resolved == "203.0.113.50") {
            "an untrusted peer's forwarded header must be ignored, got $resolved"
        }
    }

    @Test
    fun `a direct caller cannot mint unlimited buckets`() {
        val resolutions = (1..20).map { index ->
            resolver.resolve(request(peer = "203.0.113.50", forwardedFor = "10.0.0.$index"))
        }
        check(resolutions.toSet() == setOf("203.0.113.50")) {
            "every attempt from one untrusted peer must share one bucket, got ${resolutions.toSet()}"
        }
    }

    // ----------------------------------------------------------------- robustness

    @Test
    fun `falls back to the peer when the header is absent or unusable`() {
        listOf(null, "", "   ", ",", "not-an-ip", "999.999.999.999", "<script>").forEach { header ->
            val resolved = resolver.resolve(request("127.0.0.1", header))
            check(resolved == "127.0.0.1") {
                "unusable header '$header' must fall back to the peer, got $resolved"
            }
        }
    }

    @Test
    fun `never resolves a hostname from the header`() {
        // Resolving would turn a request header into an attacker-directed DNS lookup.
        val resolved = resolver.resolve(request("127.0.0.1", "example.com"))
        check(resolved == "127.0.0.1") { "a hostname must not be accepted or resolved" }
    }

    @Test
    fun `strips a port from the forwarded value`() {
        check(resolver.resolve(request("127.0.0.1", "203.0.113.7:44321")) == "203.0.113.7")
    }

    @Test
    fun `handles IPv6 clients and an IPv6 loopback proxy`() {
        check(resolver.resolve(request("::1", "2001:db8::1")) == "2001:db8:0:0:0:0:0:1")
        check(resolver.resolve(request("127.0.0.1", "[2001:db8::1]:443")) == "2001:db8:0:0:0:0:0:1")
    }

    @Test
    fun `an IPv4 client is not matched against an IPv6 trusted range`() {
        // Guards the CIDR comparison against matching across address families.
        val resolved = resolver.resolve(request(peer = "203.0.113.50", forwardedFor = "10.0.0.1"))
        check(resolved == "203.0.113.50")
    }

    @Test
    fun `with no trusted proxies configured the header is ignored entirely`() {
        val strict = ClientIpResolver(AuthProperties(trustedProxies = emptyList()))
        check(strict.resolve(request("127.0.0.1", "203.0.113.7")) == "127.0.0.1") {
            "an empty trust list must mean no header is believed"
        }
    }

    @Test
    fun `a wider trusted range can be configured for a proxy on another host`() {
        val wide = ClientIpResolver(AuthProperties(trustedProxies = listOf("10.0.0.0/8")))

        check(wide.resolve(request("10.1.2.3", "203.0.113.7")) == "203.0.113.7") {
            "a peer inside the trusted range must be believed"
        }
        check(wide.resolve(request("11.1.2.3", "203.0.113.7")) == "11.1.2.3") {
            "a peer outside the trusted range must not be"
        }
    }
}
