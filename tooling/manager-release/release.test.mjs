import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import {
    MAX_MANAGER_APK_BYTES,
    assertManagerReleaseTag,
    createManagerReleaseManifest,
    parseManagerVersionFile,
    sha256Bytes,
    writeManagerRelease,
} from "./release.mjs";

const MANAGER_VERSION = Object.freeze({ versionName: "0.0.1", versionCode: 1 });
const PUBLISHED_AT = "2026-09-02T00:00:00Z";
const APK = Buffer.from("signed-apk-fixture");

describe("ThunderManager release identity", () => {
    it("parses one canonical stable version and positive Android versionCode", () => {
        assert.deepEqual(parseManagerVersionFile("versionName=0.0.1\nversionCode=1\n"), MANAGER_VERSION);
        assert.deepEqual(parseManagerVersionFile("versionCode=1\r\nversionName=0.0.1\r\n"), MANAGER_VERSION);
        for (const invalid of [
            "versionName=0.0.1-beta.1\nversionCode=1\n",
            "versionName=01.0.0\nversionCode=1\n",
            "versionName=0.0.1\nversionCode=0\n",
            "versionName=0.0.1\nversionCode=01\n",
            "versionName=0.0.1\nversionCode=1\nextra=true\n",
            "versionName=0.0.1\nversionName=0.0.2\n",
        ]) {
            assert.throws(() => parseManagerVersionFile(invalid));
        }
    });

    it("accepts only the exact stable tag", () => {
        assert.equal(assertManagerReleaseTag("v0.0.1", MANAGER_VERSION), "0.0.1");
        for (const tag of ["0.0.1", "v0.0.2", "v0.0.1-beta.1", "latest"]) {
            assert.throws(() => assertManagerReleaseTag(tag, MANAGER_VERSION), /does not match/);
        }
    });

    it("emits the exact schema consumed by the stable update client", () => {
        const apkSha256 = sha256Bytes(APK);
        assert.deepEqual(createManagerReleaseManifest({
            apkSha256,
            apkSize: APK.length,
            managerVersion: MANAGER_VERSION,
            publishedAt: PUBLISHED_AT,
            tag: "v0.0.1",
        }), {
            schema: 1,
            version: "0.0.1",
            publishedAt: PUBLISHED_AT,
            apk: {
                url: "https://github.com/Accentaro/ThunderManager/releases/download/v0.0.1/ThunderManager-0.0.1.apk",
                size: APK.length,
                sha256: apkSha256,
            },
            notesUrl: "https://github.com/Accentaro/ThunderManager/releases/tag/v0.0.1",
        });
        assert.throws(() => createManagerReleaseManifest({
            apkSha256,
            apkSize: MAX_MANAGER_APK_BYTES + 1,
            managerVersion: MANAGER_VERSION,
            publishedAt: PUBLISHED_AT,
            tag: "v0.0.1",
        }), /size bound/);
    });

    it("writes only the versioned APK, release manifest, and checksums without overwriting", async () => {
        const temporary = mkdtempSync(path.join(tmpdir(), "thunder-manager-release-"));
        const sourceApk = path.join(temporary, "manager-release.apk");
        const output = path.join(temporary, "output");
        try {
            writeFileSync(sourceApk, APK, { flag: "wx" });
            const result = await writeManagerRelease({
                apk: sourceApk,
                managerVersion: MANAGER_VERSION,
                outputDirectory: output,
                publishedAt: PUBLISHED_AT,
                tag: "v0.0.1",
            });
            assert.deepEqual(readdirSync(output).sort(), [
                "SHA256SUMS",
                "ThunderManager-0.0.1.apk",
                "release.json",
            ]);
            assert.deepEqual(readFileSync(path.join(output, result.artifactName)), APK);
            const releaseBytes = readFileSync(path.join(output, "release.json"));
            assert.deepEqual(JSON.parse(releaseBytes.toString("utf8")), result.manifest);
            assert.equal(
                readFileSync(path.join(output, "SHA256SUMS"), "utf8"),
                result.apkSha256 + "  ThunderManager-0.0.1.apk\n"
                    + sha256Bytes(releaseBytes) + "  release.json\n",
            );
            await assert.rejects(writeManagerRelease({
                apk: sourceApk,
                managerVersion: MANAGER_VERSION,
                outputDirectory: output,
                publishedAt: PUBLISHED_AT,
                tag: "v0.0.1",
            }), /must be empty/);
        } finally {
            rmSync(temporary, { force: true, recursive: true });
        }
    });
});
