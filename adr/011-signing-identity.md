# ADR-011: Android signing identity and key recovery

Status: **Proposed**

Date: 2026-08-15

## Problem

Every patched update for one package identity must use the same signing key. Losing it forces uninstall/reinstall and Android app-data loss. Embedding the private key in the patched APK makes recovery easy but exposes the update identity to anyone who can obtain the APK.

## Evidence

Android requires certificate continuity for updates. Vendetta Manager keeps a manager-private keystore; Aliucord Manager can recover an embedded key, which improves continuity but publishes the private material with the target. See [signing research](../research/ecosystem.md#signing-identity-is-user-data).

## Alternatives

1. Generate one manager-private key with no backup.
2. Embed the private key in every patched target.
3. Use a hardware-backed non-exportable Android Keystore key.
4. Maintain a per-target exportable key encrypted at rest by a Keystore-wrapped key, with explicit user-encrypted backup/import and certificate verification.
5. Use a project/server-held universal signing key.

## Chosen approach

Choose alternative 4. Each mod package identity has a stable key ID/certificate and encrypted private material in manager-private storage. Android Keystore protects the local wrapping key. Optional backup re-encrypts the identity with a user-held recovery secret using reviewed memory-hard KDF and AEAD choices. The target stores only public certificate/key ID metadata. No server receives the private key.

Exact cryptographic formats/parameters and authentication policy remain blocked on a security prototype/review.

## Benefits

- Supports manager reinstall/device migration without publishing the update key.
- Limits one compromised identity from updating all users/packages.
- Makes signing continuity auditable before destructive transitions.
- Avoids a high-value project-wide universal key.

## Drawbacks

- Exportability is weaker than a hardware-only key against device compromise.
- Backup UX and secret loss remain user burdens.
- Multiple target identities complicate management.
- Cryptographic/key migration mistakes are high impact.

## Update risks

- Android Keystore invalidation, backup format/KDF aging, manager data loss, certificate algorithm policy changes, and package identity confusion.

## Validation

Threat-model and independently review the format. Test key generation/use on supported Android versions, biometric/lock changes, Keystore invalidation, backup round-trip across devices, wrong/corrupt secrets, legacy format migration, signing challenge verification, and proof that APK/log/diagnostic outputs contain no private key. The manager must test backup recovery before an operation that would make key loss destructive when the user opts in.
