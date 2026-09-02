# ADR-016: Rootless derived-package delivery

Status: **Accepted; implemented and qualified on one Android/Discord tuple**

Date: 2026-08-28

Supersedes [ADR-015](015-patchless-framework-injection.md) as Thunder's user-facing delivery path
and extends the purpose-built backend selected by [ADR-013](013-purpose-built-injection-backend.md).

## Problem

Thunder must work on a stock, non-rooted Android device. The user's official Discord installation
must keep its Play Store signer, installer ownership, bytes, data, and update path. After Thunder is
installed, updating it for a newer Discord build must not require uninstalling either app.

Android cannot install modified code into `com.discord` without Discord's private signing key.
Root, Zygisk, and an Xposed framework can inject without changing that package, but they are not a
rootless solution. Shizuku can submit an install session but cannot bypass Android's package-name or
certificate checks.

## Decision

Use Thunder's purpose-built APK backend as the only injection backend and install its output as a
derived package beside Discord:

```text
official source: com.discord
Thunder output:  dev.thunder.app
```

The manager always authenticates and hash-snapshots the complete installed official APK set first.
For a fresh clone or a newer source-version/content update, that stock snapshot is the backend
input. For `CURRENT_CLONE_REFRESH`, Manager first validates the clone, persistent key, marker,
version, and full split closure, then snapshots the authenticated installed clone and discards the
official snapshot before invoking the backend. It never writes to, replaces, re-signs, downgrades,
or uninstalls `com.discord`. If Discord is absent, the APK picker hands the chosen file to Android's
normal installer unchanged; Thunder rescans only after that install.

For a compatible, trusted source set, the backend:

1. changes the root manifest package in the base APK and every configuration split;
2. changes package-owned custom permission declarations and matching references;
3. changes package-owned provider authorities;
4. gives the base application and labelled launcher components the visible name `Thunder`;
5. moves package-owned task affinities into the `dev.thunder.app.*` namespace, while preserving
   `com.discord.*` component class names and intent action/category strings, Android/external
   routes, metadata, resources, native libraries, and all unrelated fields; the intent strings stay
   unchanged because the narrow DEX seam does not rewrite their routing call sites;
6. rewrites only `ResTable_package.name` in each resource-bearing `resources.arsc` from the verified
   source package to `dev.thunder.app`, preserving resource package ID `0x7f` and all other table
   bytes;
7. applies the qualified AppComponentFactory, bootstrap DEX, React Native seam, recovery activity,
   authenticated runtime, and schema-3 provenance marker; and
8. signs and verifies the complete output set with one persistent key owned by
   `dev.thunder.app`, then submits it through Android's standard `PackageInstaller` confirmation.

Every source signer is checked against Thunder's supported official Discord signer set. Every
archive mutation is checked against an allowlist. An existing `dev.thunder.app` is updated only if
its signer matches the stored Thunder identity and its marker proves its source/output identity.
Thunder never removes a conflicting or unverifiable package automatically.

## Update behaviour

The Play Store continues to own and update `com.discord`. A Play update does not mutate the separate
Thunder clone. Manager compares the official source version and provenance with the installed clone:

- no clone: **Inject Thunder** creates `dev.thunder.app`;
- current clone: **Open Thunder** launches `dev.thunder.app`, while **Refresh Thunder** authenticates
  the matching official source and installed clone, then rebuilds from the clone snapshot when the
  Manager/bootstrap/runtime changed;
- newer compatible official source: **Update Thunder** rebuilds from that source and installs an
  in-place update signed by the same Thunder key.

Because the output package and signing identity stay stable, Android preserves Thunder's app-private
data across those updates. Thunder and official Discord intentionally have separate app sandboxes,
accounts, notifications, and settings.

Schema 3 records the exact SHA-256 of the rewritten React Native host DEX. A stock injection still
rewrites and structurally verifies the seam before recording that digest. An existing schema-2 clone
is accepted only for one migration Refresh, which performs the structural check and emits schema 3.
Later Refresh operations stream and authenticate the unchanged host DEX instead of retaining or
parsing it, and independently require the output digest and marker to match.

That one schema-2 migration also corrects the earlier clone-manifest policy. It requires an
authenticated schema-2 marker and a manifest already owned by the installed output package. It
normalizes source-owned task affinities, provider-authority segments, and custom permission
declarations/references to the output package, but restores only output-owned intent action/category
strings to the marker's supported source package because the DEX routing call sites remain
unchanged. External authority segments, component class names, metadata, labels, and every other
field stay byte-exact. Schema-3 inputs never invoke this migration and preserve their manifests.

## Rejected alternatives

1. Re-sign and replace `com.discord`: breaks Play signing continuity and requires destructive
   uninstall/reinstall migration.
2. Retain modern libxposed as a second backend: adds root/framework requirements and two competing
   product flows without helping the requested stock-device path.
3. Vendor LSPatch: adds a large generic hook/native toolchain and licensing surface when Thunder's
   smaller owned backend already qualifies the required seam.
4. Present Shizuku or a secondary Android profile as a signing workaround: neither changes the
   device-global package certificate rule.

## Consequences and limits

- Official Discord remains eligible for normal Play updates because it is never the install target.
- Updating official Discord and updating Thunder are two separate actions; Manager cannot honestly
  claim that Play updates `dev.thunder.app` automatically.
- The first clone install starts with a separate data sandbox. Android cannot share or migrate the
  official app's private data into it.
- The clone has a Thunder-derived signer, not Discord's Play signer. Play Integrity
  app-recognition/licensing, Play Billing ownership, or host certificate/package pinning may reject
  clone-only paths; this architecture does not bypass those checks.
- Rewriting the host DEX can make source baseline-profile records keyed to the old DEX checksum
  inapplicable. ART falls back to ordinary compilation/JIT, but equivalent startup performance is
  not claimed until regenerated profiles are qualified.
- The signing identity survives Manager APK updates. Uninstalling Manager, clearing its data, or
  losing its Android Keystore material can make an existing clone impossible to update; Manager
  must report that condition and must not silently generate a replacement key.
- A Discord release that moves the qualified bootstrap seam is blocked before installation until
  the compatibility backend is updated.

## Validation

Release qualification must prove on a locked stock device that:

- official Discord and Thunder coexist;
- the official package's signer, complete split hashes, installer/update owner, first-install time,
  and data remain unchanged throughout injection;
- the derived package has the expected ID, marker, signer, split closure, and visible label;
- Thunder reaches runtime-ready after a cold launch;
- a later same-signer Thunder install updates in place while preserving clone first-install time and
  an app-data sentinel; and
- malformed sources, untrusted signers, incompatible seams, foreign clones, missing identities,
  signer mismatches, and downgrades all fail closed without an uninstall path.

The first end-to-end qualification completed on 2026-08-29 using a locked Samsung SM-S918B on
Android 16 and Discord `344.5 Alpha` (`versionCode=344205`). Official `com.discord` remained
Play-signed, Play-owned, and byte-for-byte unchanged. `dev.thunder.app` installed as the complete
four-APK set with its persistent Thunder signer. A subsequent Refresh retained UID `10523` and
`firstInstallTime=2026-08-29 00:35:14` while advancing `lastUpdateTime`, proving an in-place update.
That recorded update rebuilt the current official stock set into the already installed clone
identity and emitted schema 3; it did not exercise schema-2 migration. With final Manager wiring,
normal same-version Refresh selects the authenticated installed clone and uses the schema-3 fast
path described above.

Backend 0.6.0 read-back verified `dev.thunder.app` and resource ID `0x7f` in the base, English, and
xxhdpi resource tables; the arm64 split has no resource table. The welcome UI rendered, Thunder's
React capability installed, Discord ran, and the host settled with 3,633 modules. The final launch
and four additional cold relaunches produced no invalid-resource message, crash, or ANR. This closes
the rootless-flow gate for that exact tuple, not the broader host/channel/OEM matrix. Detailed
evidence is recorded in
[vertical slice 016](../docs/implementation/vertical-slice-016-rootless-clone.md).
