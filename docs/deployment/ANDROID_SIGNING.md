# Android release signing (V2)

**Status: no V2 release keystore exists yet. This is deliberate.**

A release signing key is a long-lived identity: whoever holds it can publish updates to
every installed copy of the app, and if it is lost, installed apps can never be updated in
place again. It must therefore be generated on the owner's own secure machine, never in a
cloud session, a CI runner or an ephemeral environment. Nothing in this repository
generates one, and no keystore, password or alias is committed.

## What the build already supports

Both app modules pick up release signing **only** when the material is supplied, and
otherwise leave the release APK unsigned rather than silently falling back to the debug
key — which is what Demo v1 did.

Two equivalent sources, checked in this order:

1. `android/keystore.properties` — gitignored:
   ```properties
   storeFile=C:/secure/path/orszem-v2-release.jks
   storePassword=...
   keyAlias=orszem-v2
   keyPassword=...
   ```
2. Environment variables:
   ```
   ORSZEM_RELEASE_STORE_FILE
   ORSZEM_RELEASE_STORE_PASSWORD
   ORSZEM_RELEASE_KEY_ALIAS
   ORSZEM_RELEASE_KEY_PASSWORD
   ```

If any of the four is missing, `assembleRelease` produces `*-release-unsigned.apk`. CI
never needs any of them, because CI builds debug only.

## Generating the V2 key — on the owner's secure machine

Do this once, offline, on a machine you control.

```bash
keytool -genkeypair -v \
  -keystore orszem-v2-release.jks \
  -alias orszem-v2 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

- `-validity 10000` is roughly 27 years. A key that expires before the app is retired
  makes updates impossible, so do not shorten it.
- Use a long random passphrase from a password manager. The store and key passwords may
  be the same for a PKCS12 keystore.
- The distinguished name identifies the publisher and is embedded in every APK, so use
  real details.

Record the certificate fingerprint for later verification:

```bash
keytool -list -v -keystore orszem-v2-release.jks -alias orszem-v2 | grep SHA256
```

## Back it up before the first release

Before any V2 build is distributed:

- Store the keystore file in at least two durable places you control, at least one offline.
- Store the passwords in your password manager, not next to the keystore.
- Record the SHA-256 certificate fingerprint somewhere you can find it.

Losing the key means every installed V2 app is permanently unupdatable and must be
reinstalled under a new application ID.

## Do not reuse the V1 identity

Demo v1.1 was distributed with a different identity, measured and recorded in
`docs/archive/DEMO_V1_1.md`. V2 has new application IDs (`hu.orszembejelento.app`,
`hu.orszembejelento.service`), so there is no upgrade path from V1 to V2 and no technical
reason to reuse the old key. Keep them separate: the V1 key remains only for the V1
application IDs, and should still be backed up for that purpose.

## Google Play

If V2 is published through Google Play, enrol in **Play App Signing**. Google then holds
the app signing key and this keystore becomes the *upload* key, which can be reset by
support if lost. That materially reduces the risk described above, and is recommended.
