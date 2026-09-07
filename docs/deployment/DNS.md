# DNS for orszembejelento.hu

**Status: NOT CONFIGURED.** Verified on 2026-09-07 from this workstation:

```
orszembejelento.hu       -> no A record
www.orszembejelento.hu   -> no A record
api.orszembejelento.hu   -> no A record
orszembejelento.hu NS    -> no NS records
```

The zone has no nameserver records at all, so it is **not delegated yet**. Until that is
fixed, no name resolves, Let's Encrypt cannot complete an HTTP-01 challenge, and **no
HTTPS certificate can be issued**. Nothing in this repository claims otherwise.

## 1. Delegate the zone

At the registrar, point `orszembejelento.hu` at the nameservers of whichever DNS provider
will host the zone. Until `dig NS orszembejelento.hu` returns records, step 2 has no effect.

## 2. Records to create

All three names point at the **same** machine — the existing Oracle Cloud VM, which is
retained for V2.

| Name | Type | Value | TTL |
|---|---|---|---|
| `orszembejelento.hu.` | `A` | *server public IPv4* | 300 |
| `www.orszembejelento.hu.` | `A` | *server public IPv4* | 300 |
| `api.orszembejelento.hu.` | `A` | *server public IPv4* | 300 |

A short TTL of 300 seconds during rollout makes mistakes cheap to correct; raise it once
things are stable.

There is deliberately **no `service.orszembejelento.hu`**: the Service client is Android
only, and there is no Service Web in V2.

### About the IP address

Demo v1 documentation records the VM's public IPv4 as `129.159.31.175`. **Re-verify it in
the OCI console before creating these records** rather than trusting the archived value.

> **The public IP must not be released.** These records depend on it, and the same VM is
> the intended V2 host. See `V1_DECOMMISSION.md` — retiring the V1 *application* does not
> mean giving up the address.

## 3. Verify before deploying Caddy

```bash
dig +short NS orszembejelento.hu
dig +short A orszembejelento.hu
dig +short A www.orszembejelento.hu
dig +short A api.orszembejelento.hu
```

All three A lookups must return the VM's address. Only then start Caddy with the V2
configuration; it will request certificates automatically over HTTP-01, which requires
inbound TCP/80 to be open in both `firewalld` and the OCI security list.

## 4. What each name serves

| Name | Serves |
|---|---|
| `orszembejelento.hu` | the static Public Web build, plus `/api/*` proxied to the backend |
| `www.orszembejelento.hu` | a permanent redirect to the apex |
| `api.orszembejelento.hu` | the same backend, for the Android clients |

Both API routes reach the same backend and the same use cases; the API is never
implemented twice.
