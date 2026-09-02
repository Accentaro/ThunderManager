# ThunderManager

ThunderManager is the Android companion for [Thunder](https://github.com/Accentaro/Thunder). It creates and maintains a rootless `dev.thunder.app` clone beside an official Discord installation, without replacing or uninstalling that official app. ThunderManager and Thunder have independent versions and release histories.

## Install and inject

ThunderManager requires Android 9 or newer. Download `ThunderManager-<version>.apk` from the repository's [latest stable release](https://github.com/Accentaro/ThunderManager/releases/latest), verify it against the release's `SHA256SUMS`, and install it. Android will ask you to allow installs from ThunderManager when an APK is ready.

Keep a trusted official Discord package installed. Open ThunderManager, select the detected Discord channel, and tap **Inject Thunder**. Confirm Android's installer prompt; the result is the separate `dev.thunder.app` package with its own app data. If no installed source is available, **Choose Discord APK** hands the selected APK to Android for normal installation before it is used as a source.

## Keep Thunder and ThunderManager current

- **Thunder release update:** ThunderManager checks stable Thunder releases, downloads the exact runtime asset over HTTPS, verifies its declared size and SHA-256 digest, and reuses the normal signed clone-update pipeline. Tap **Update Thunder** in the release notice and confirm Android's installer.
- **Discord refresh:** when official Discord has a newer version, the main **Update Thunder** action rebuilds the clone from that trusted source. When its version is unchanged, **Refresh Thunder** reapplies the current verified runtime. Both are in-place clone updates and retain the clone's data and signing identity.
- **ThunderManager self-update:** **Update ThunderManager** downloads and verifies the stable APK, checks its package, version, and permanent production signer, then opens Android's standard update confirmation. A different signer or a downgrade is rejected.

Automatic release checks are cached for about six hours. The refresh button performs a forced check. ThunderManager accepts only stable SemVer releases and never downgrades either product.

## Back up the Thunder signing identity

The first injection creates a device-local key that signs `dev.thunder.app`; Android requires that same identity for every later in-place clone update. Immediately after the first successful injection, use **Back up identity**, choose a strong unique password, and save `Thunder-signing-identity.thunderkey` somewhere outside the device as well as in a second protected location. Keep the backup file and its password separately. This is the user's clone-update identity, not the repository's ThunderManager release keystore.

After reinstalling ThunderManager or moving to another device, use **Restore identity** before attempting to update an existing clone. The Manager validates the encrypted backup and, when a clone is installed, requires its certificate to match. Replacing an active identity requires explicit confirmation. A lost backup or password cannot be recovered, and creating a different key cannot update an already installed clone.

## Build and verify

Prerequisites are Node.js 24.12, pnpm 11.21, JDK 17, and Android SDK platform/build-tools 36. From a clean checkout:

```sh
pnpm install --frozen-lockfile
pnpm verify
pnpm verify:staging
```

The Manager does not contain or build Thunder's TypeScript source. Its fallback `runtime.js` is fetched from the immutable Thunder release pinned by `gradle/thunder-runtime-release.properties` and is accepted only when its exact size and SHA-256 match. An already downloaded copy may be supplied with `THUNDER_RUNTIME_FILE` (or `-Pthunder.runtimeFile=...`) for an offline build; the same pins still apply and the file must remain untracked. See [the runtime pin policy](docs/operations/manager-runtime-release-pin.md).

## Release

`apps/manager/version.properties` is the sole version source. Increase both stable `versionName` and Android `versionCode`, qualify the release, and push the exact `v<versionName>` tag. The release workflow uses the permanent certificate pin and four protected repository secrets to build the signed APK, verify it, create `release.json` and `SHA256SUMS`, and publish the immutable asset set. No keystore or password belongs in a checkout. Maintainers should follow [the release runbook](docs/operations/release.md).

## License

ThunderManager is licensed under the [GNU General Public License v3.0 only](LICENSE). Discord is a trademark of Discord Inc.; ThunderManager is an independent project and is not affiliated with Discord Inc.
