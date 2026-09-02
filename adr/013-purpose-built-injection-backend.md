# ADR-013: Purpose-built injection backend

Status: **Accepted; APK mutation, runtime attachment, and readiness seam implemented**

Date: 2026-08-15

## Problem

Thunder needs a rootless bootstrap without adopting the maintenance, API, binary, and licensing surface of a general Xposed/LSPatch stack. A generic ZIP rewrite can also move uncompressed native libraries and break Android packages whose loader depends on their page alignment.

## Decision

Implement a narrow backend owned entirely by Thunder:

1. require a supported Discord package, one base APK, a contiguous DEX set, an existing string-valued `android:appComponentFactory`, and exactly two typed `ReactInstance.loadJSBundle(JSBundleLoader)` calls in one host DEX;
2. rebuild only the binary XML string pool, factory value, and a native recovery-activity declaration whose Android attribute resource IDs are proven before writing;
3. replace the two React Native calls with same-width, same-register static bridge calls and reject every other semantic DEX change;
4. preserve the original local ZIP region and every retained local-entry offset;
5. remove the old APK signing block and obsolete JAR-signature entries;
6. append one aligned `classesN.dex`, authenticated bootstrap/runtime configuration, a bounded source-JavaScript recovery baseline, and the patch manifest;
7. rebuild the central directory, independently compare all non-allowlisted entry metadata, copy configuration splits byte-for-byte, then sign and verify the complete APK set.

The bridge verifies the embedded runtime SHA-256 again inside the target process, registers a React Native marker listener, and schedules Thunder's asset through the host's exact `JSBundleLoader` type before delegating Discord's bundle. This lets Thunder observe Metro definitions without destructively reopening Metro's module collection after startup. Boot health is acknowledged only when React Native emits `RUN_JS_BUNDLE_END` for `runtime.js`. The baseline is source JavaScript so recovery does not depend on one Hermes bytecode version; signed HBC A/B slots remain the update path after host qualification.

The bootstrap DEX is generated during the build with pinned D8 36.0.0 from Thunder's release bootstrap AAR. No prebuilt loader is checked in or downloaded.

## Evidence

- Synthetic tests cover binary string-pool growth, factory/recovery declaration, DEX selection, signature removal, retained native/content preservation, split preservation, bridge delegation, exact runtime-completion filtering, immutable runtime installation, settings-definition observation, and stock/refresh mutation reports.
- A private read-only probe against `com.discord` 343.5 Alpha rewrote its actual manifest, found two React Native calls in `classes3.dex`, converted them to same-width bridge calls, selected `classes5.dex`, and passed class/field/annotation/method/try-range/instruction verification. Temporary host/candidate files were deleted.
- The connected manager prepared a four-APK private candidate with six allowlisted base changes, including the 574-byte runtime asset, signed and verified the complete set, blocked installation because replacement would require uninstall, and discarded the transaction cleanly. Its transaction directory was empty afterward and Discord's installed package paths did not change.

## Consequences

The APK mutation is much smaller and more auditable than a general hook framework, and its code/license ownership is clear. It intentionally supports fewer host layouts and fails closed when the existing factory or ZIP32 assumptions do not hold.

The bridge now owns a narrow authenticated runtime attachment and fails closed for an unexpected receiver, loader, asset hash, or completion marker. Android package signing identity is device-global, so a secondary user/profile cannot host a Thunder-signed `com.discord` while official Discord remains installed for the owner. One explicitly authorized owner-device replacement qualified Discord Alpha 343205 on Android 16, including native ART preflight, launch, runtime readiness, disable-runtime recovery, and return to normal. That single result does not replace the required multi-version/device matrix or production backup/install UX.
