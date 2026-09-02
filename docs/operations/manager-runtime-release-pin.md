# ThunderManager runtime release pin

ThunderManager does not build Thunder's JavaScript runtime. Its packaged fallback runtime is the
exact `runtime.js` asset from one immutable, stable Thunder release. The tracked
`gradle/thunder-runtime-release.properties` file is the sole build-time contract between the two
repositories: it pins the stable version, canonical GitHub release URL, exact byte size, and
SHA-256 digest.

The `:android:injection-custom:generateRuntimeAssets` task downloads that exact HTTPS asset and
refuses redirects to a non-HTTPS URL, more than five redirects, unexpected status or size, and any
digest mismatch. The verified file is written atomically under the module's ignored `build/`
directory before Android asset merging. It is never generated from `packages/runtime` or
`packages/discord-compat`, and a clean ThunderManager checkout needs neither package.

For release bootstrapping or an offline build, supply a previously downloaded copy with either
`-Pthunder.runtimeFile=<path>` or `THUNDER_RUNTIME_FILE=<path>`. An override only changes where the
bytes come from; the same tracked size and SHA-256 pins are always enforced. Do not weaken or bypass
verification when a release is unavailable.

To advance the packaged fallback in a later ThunderManager release:

1. Publish and finish qualifying the new stable Thunder release first.
2. Download its `runtime.js`, verify it against that release's `release.json` and `SHA256SUMS`, and
   record the canonical release URL, exact size, and lowercase SHA-256 in the pin file.
3. Run a clean Manager build without either local override so the normal immutable-release path is
   exercised.
4. Treat any pin change as a security-sensitive release change and review it explicitly.

Changing Thunder source files alone can never change a ThunderManager APK. Changing a remote asset
at the pinned URL cannot change the APK either unless its bytes still match the committed size and
digest; immutable GitHub Releases provide the additional server-side protection.
