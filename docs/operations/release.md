# Manager release runbook

## Permanent release identity and repository secrets

`apps/manager/version.properties` is the sole ThunderManager release-version source. It starts at
stable SemVer `0.0.1` and Android `versionCode` `1`. Every release must increase `versionCode`, set
the desired stable SemVer in that file, and use the exact tag `v<versionName>`. Debug and staging
suffixes do not alter the stable release identity.

The `Accentaro/ThunderManager` repository requires these four Actions secrets:

- `THUNDER_RELEASE_KEYSTORE_BASE64`: base64 of the complete permanent release keystore file;
- `THUNDER_RELEASE_STORE_PASSWORD`: that keystore's store password;
- `THUNDER_RELEASE_KEY_ALIAS`: the permanent private-key alias;
- `THUNDER_RELEASE_KEY_PASSWORD`: that private key's password.

Set secret values through `gh secret set` standard input or GitHub's encrypted secret form. Never
place a value in a command argument, workflow file, shell history, issue, release asset, or build
log. The local keystore remains outside every checkout. Supplying its base64 as a repository secret
does not replace the separately protected offline backup.

The public SHA-256 digest in `apps/manager/release-certificate.sha256` pins every release to the
first permanent production certificate. CI rejects both a replacement keystore secret and an APK
signed by any other certificate. Updating that digest is a signing-key rotation and must never be
part of a normal release.

On an exact stable version tag, `.github/workflows/release-manager.yml` rejects an existing release,
runs the release-tooling and Android tests, decodes the keystore with owner-only permissions inside
the ephemeral runner's temporary directory, disables Gradle build/configuration caches, and builds
the minified release. It then requires:

- package ID `dev.thunder.manager`;
- the canonical `versionName` and `versionCode`;
- an APK between 1 byte and 256 MiB;
- APK Signature Scheme v3 with v1 disabled (v2 may be omitted for the minSdk 28 output); and
- exactly one APK certificate whose SHA-256 digest matches the certificate exported from the
  supplied keystore.

Only after those checks does the workflow emit `ThunderManager-<version>.apk`, `release.json`, and
`SHA256SUMS`. `release.json` uses the exact schema consumed by the Manager update client. The
output directory must be empty, the workflow refuses an existing tag release, and `gh release
create --verify-tag` uploads the complete asset set before publishing the stable release. Temporary
keystore and Gradle state are removed by an `always()` cleanup step; the hosted runner is also
ephemeral.

Thunder manager release packaging is fail-closed. A release cannot be emitted without all four signing environment variables:

- `THUNDER_RELEASE_STORE_FILE`: absolute path to the release keystore;
- `THUNDER_RELEASE_STORE_PASSWORD`: keystore password;
- `THUNDER_RELEASE_KEY_ALIAS`: signing-key alias;
- `THUNDER_RELEASE_KEY_PASSWORD`: signing-key password.

The keystore and credentials must remain outside the repository. Release verification requires APK Signature Scheme v3 and requires v1 to be disabled. The currently verified minSdk 28 APK is v3-only even though the Android signing configuration requests v2, v3, and v4; an additional valid v2 signature would not change the signer-continuity guarantee. If the variables are incomplete, Android's `validateSigningRelease` task fails against the intentionally absent `.release-signing-required` sentinel.

## Qualification sequence

1. Run `pnpm verify`.
2. Run `pnpm verify:staging`. Staging uses the release optimizer and resource shrinker but has a separate application ID and the Android debug signing identity.
3. Export all release-signing variables in the current process.
4. Run `gradlew.bat :apps:manager:clean :apps:manager:assembleRelease :apps:manager:lintRelease`.
5. Verify the APK with Android build-tools `apksigner verify --verbose --print-certs` and record its SHA-256 digest.
6. Generate local release metadata only into a new empty directory:
   `pnpm release:manager --tag vX.Y.Z --apk <verified-apk> --output <empty-directory>`.
7. Inspect the manager APK. Require the purpose-built bootstrap/runtime assets and PackageInstaller
   receiver; reject all libxposed dependencies, Xposed metadata, and delete-package permissions.
8. On a locked stock device, record official Discord's signer certificate, complete installed APK-set
   hashes, first-install time, installer/update owner, version, and a data sentinel.
9. Install or update only Thunder Manager. Use **Inject Thunder** and Android's visible confirmation
   to create `dev.thunder.app`. Prove its package ID, complete split closure, persistent Thunder
   signer, schema-3 provenance marker and host-DEX digest, visible label, and runtime-ready
   milestone.
10. Repeat the official Discord measurements. Its signer, hashes, first-install time, installer/update
    owner, version, and data must be identical to the pre-injection record.
11. Without changing the official source, use **Refresh Thunder**. Prove Manager authenticates and
    hash-snapshots official Discord, validates clone/key/marker/version/full split closure, discards
    the official workspace, and passes the installed clone snapshot to the schema-3 fast path.
    Require exact host-DEX preservation, an in-place update, retained clone data/first-install time,
    and no APK growth across repeated equivalent Refresh operations. Require a retained local file
    header at absolute byte 0, preserved native alignment up to 16 KiB, and complete-set signer
    verification with `apksigner verify --min-sdk-version 28`. If PSS is sampled rather than traced,
    report only the maximum observed sample and do not label it the process peak.
12. Apply an official matching-signer Discord update without removing either app. Use **Update
    Thunder**, then prove the clone updates with the same signer and preserves clone first-install
    time and data. Prove official Discord still belongs to its original installer.
13. Exercise an incompatible seam, untrusted source signer, invalid/foreign clone marker, missing
    stored identity, signer mismatch, and downgrade. Every case must fail closed without uninstalling
    either package.

The staging package is not a distributable release. A successful manager build also does not qualify
a Discord injection backend or Hermes runtime. Those require the real-host/device matrix described
in the architecture documents. Qualify each supported channel/build separately; a passing DEX seam
on one build does not prove another build is compatible.

The clean-checkout workflow pins each third-party action to a full commit, installs the exact Node/pnpm/JDK and Android platform versions used by the project, runs `pnpm verify`, then runs the staging R8/resource-shrinker gate. Renovation of an action pin requires reviewing its upstream immutable release and updating the version comment with the digest.

## Device safety gate

Never uninstall or replace a user's official Discord installation during routine manager
qualification. Thunder output must use a distinct derived package ID. The APK-picker path hands the
selected source URI to Android's installer unchanged. An installed, trusted official package always
establishes source provenance. Only after that check may an authenticated current clone become the
mutation input for a same-version Refresh; fresh and changed-source builds use official stock.

Never automatically uninstall an existing Thunder clone. If its signer/marker differs, its version
is newer, or the Manager signing identity is unavailable, block with an actionable explanation.
Generating a new key while a clone exists does not repair continuity and is forbidden.

Users already on a legacy Thunder-signed `com.discord` cannot regain the official signing lineage in
place. Restoring the official app is a separate, destructive migration and must not be presented as
part of **Inject Thunder**.

A secondary Android user, work profile, or secure folder is not an isolation boundary for this signer transition: package APK and certificate identity are device-global even though data/install state varies by user. Use a separate disposable device/emulator, or a separately qualified derived-package build. Do not label a profile-only test plan safe.
