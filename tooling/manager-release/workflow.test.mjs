import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const root = path.resolve(import.meta.dirname, "../..");
const workflow = readFileSync(path.join(root, ".github/workflows/release-manager.yml"), "utf8");
const wrapperProperties = readFileSync(path.join(root, "gradle/wrapper/gradle-wrapper.properties"), "utf8");

describe("ThunderManager release workflow policy", () => {
    it("uses only the four documented repository secrets", () => {
        const names = new Set(
            [...workflow.matchAll(/\$\{\{\s*secrets\.([A-Z0-9_]+)\s*\}\}/g)]
                .map((match) => match[1]),
        );
        assert.deepEqual([...names].sort(), [
            "THUNDER_RELEASE_KEYSTORE_BASE64",
            "THUNDER_RELEASE_KEY_ALIAS",
            "THUNDER_RELEASE_KEY_PASSWORD",
            "THUNDER_RELEASE_STORE_PASSWORD",
        ]);
    });

    it("pins every action to a full commit and grants write only to the release job", () => {
        const actions = [...workflow.matchAll(/^\s*uses:\s*([^\s#]+).*$/gm)].map((match) => match[1]);
        assert.ok(actions.length >= 3);
        for (const action of actions) {
            assert.match(action, /^[^@\s]+@[0-9a-f]{40}$/);
        }
        assert.match(workflow, /^permissions:\n  contents: read$/m);
        assert.match(workflow, /^    permissions:\n      contents: write$/m);
    });

    it("publishes only tag commits contained in reviewed main", () => {
        assert.match(workflow, /fetch-depth: 0/);
        assert.match(workflow, /git fetch --no-tags origin "\+refs\/heads\/main:refs\/remotes\/origin\/main"/);
        assert.match(workflow, /git merge-base --is-ancestor "\$GITHUB_SHA" refs\/remotes\/origin\/main/);

        const ancestryGuard = workflow.indexOf("- name: Require the release commit to be on reviewed main");
        const publication = workflow.indexOf("gh release create");
        assert(ancestryGuard >= 0 && publication > ancestryGuard);
    });

    it("keeps signing state ephemeral and stages all assets before stable publication", () => {
        assert.match(workflow, /RELEASE_KEYSTORE: \$\{\{ runner\.temp \}\}/);
        assert.doesNotMatch(workflow, /^    env:/m);
        assert.match(workflow, /base64 --decode > "\$partial"/);
        assert.doesNotMatch(workflow, /base64 --decode --strict/);
        assert.match(workflow, /--no-build-cache --no-configuration-cache/);
        assert.match(workflow, /gh release create "\$GITHUB_REF_NAME"/);
        assert.match(workflow, /--verify-tag/);
        assert.doesNotMatch(workflow, /action-gh-release|--prerelease|--draft/);
        assert.match(workflow, /- name: Remove temporary signing and Gradle state\n        if: always\(\)/);
    });

    it("pins releases to the permanent production certificate", () => {
        assert.match(workflow, /apps\/manager\/release-certificate\.sha256/);
        assert.match(workflow, /Protected release keystore does not match the permanent production signer/);
        assert.match(workflow, /Release APK does not use the permanent production signer/);
    });

    it("verifies the API 28 APK with pinned Android build tools", () => {
        assert.match(workflow, /ANDROID_HOME\/cmdline-tools\/latest\/bin\/sdkmanager/);
        assert.match(workflow, /build-tools\/36\.0\.0\/aapt2/);
        assert.match(workflow, /build-tools\/36\.0\.0\/apksigner/);
        assert.match(workflow, /Verified using v1 scheme \(JAR signing\): false/);
        assert.match(workflow, /Verified using v3 scheme \(APK Signature Scheme v3\): true/);
    });

    it("pins the Gradle wrapper distribution to its official SHA-256", () => {
        assert.match(wrapperProperties, /distributionUrl=https\\:\/\/services\.gradle\.org\/distributions\/gradle-8\.14\.3-bin\.zip/);
        assert.match(wrapperProperties, /distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531/);
    });
});
