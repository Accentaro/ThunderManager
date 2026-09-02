import { createHash } from "node:crypto";
import { constants, createReadStream } from "node:fs";
import { copyFile, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";

export const DEFAULT_MANAGER_RELEASE_REPOSITORY = "Accentaro/ThunderManager";
export const MAX_MANAGER_APK_BYTES = 256 * 1024 * 1024;
export const MAX_ANDROID_VERSION_CODE = 2_100_000_000;

const REPOSITORY = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const SHA256 = /^[0-9a-f]{64}$/;
const STABLE_SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;

export function requireStableVersion(value) {
    if (typeof value !== "string" || !STABLE_SEMVER.test(value)) {
        throw new TypeError("ThunderManager version must be stable semantic versioning without a prefix or suffix");
    }
    return value;
}

export function parseManagerVersionFile(text) {
    if (typeof text !== "string" || text.includes("\0")) {
        throw new TypeError("ThunderManager version file is invalid");
    }
    const lines = text.replaceAll("\r\n", "\n").split("\n");
    if (lines.at(-1) === "") lines.pop();
    if (lines.length !== 2) throw new TypeError("ThunderManager version file must contain exactly two fields");

    const values = new Map();
    for (const line of lines) {
        const match = /^(versionName|versionCode)=([^\s]+)$/.exec(line);
        if (!match || values.has(match[1])) {
            throw new TypeError("ThunderManager version file has unknown, duplicate, or malformed fields");
        }
        values.set(match[1], match[2]);
    }
    if (values.size !== 2) throw new TypeError("ThunderManager version file is incomplete");

    const versionName = requireStableVersion(values.get("versionName"));
    const rawVersionCode = values.get("versionCode");
    if (!/^[1-9][0-9]*$/.test(rawVersionCode)) {
        throw new TypeError("ThunderManager versionCode must be a positive base-10 integer");
    }
    const versionCode = Number(rawVersionCode);
    if (!Number.isSafeInteger(versionCode) || versionCode > MAX_ANDROID_VERSION_CODE) {
        throw new RangeError("ThunderManager versionCode is outside Android's supported range");
    }
    return Object.freeze({ versionName, versionCode });
}

export async function readManagerVersion(versionFile) {
    return parseManagerVersionFile(await readFile(versionFile, "utf8"));
}

export function assertManagerReleaseTag(tag, version) {
    const stableVersion = requireStableVersion(version.versionName);
    if (tag !== "v" + stableVersion) {
        throw new Error("Release tag " + tag + " does not match ThunderManager version " + stableVersion);
    }
    return stableVersion;
}

function requirePublishedAt(value) {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.getTime())) throw new TypeError("Release publication time is invalid");
    const canonical = parsed.toISOString();
    if (value !== canonical && value !== canonical.replace(".000Z", "Z")) {
        throw new TypeError("Release publication time must be a canonical UTC ISO-8601 timestamp");
    }
    return value;
}

function requireRepository(value) {
    const [owner, repository] = value.split("/");
    if (!REPOSITORY.test(value) || owner === "." || owner === ".." || repository === "." || repository === "..") {
        throw new TypeError("Release repository is invalid");
    }
    return value;
}

function requireApkMetadata(size, digest) {
    if (!Number.isSafeInteger(size) || size < 1 || size > MAX_MANAGER_APK_BYTES) {
        throw new RangeError("ThunderManager APK is outside its size bound");
    }
    if (typeof digest !== "string" || !SHA256.test(digest)) {
        throw new TypeError("ThunderManager APK SHA-256 is invalid");
    }
}

export function createManagerReleaseManifest(options) {
    const version = assertManagerReleaseTag(options.tag, options.managerVersion);
    const repository = requireRepository(options.repository ?? DEFAULT_MANAGER_RELEASE_REPOSITORY);
    const publishedAt = requirePublishedAt(options.publishedAt);
    requireApkMetadata(options.apkSize, options.apkSha256);
    const releaseBase = "https://github.com/" + repository + "/releases";
    const artifactName = "ThunderManager-" + version + ".apk";

    return Object.freeze({
        schema: 1,
        version,
        publishedAt,
        apk: Object.freeze({
            url: releaseBase + "/download/" + options.tag + "/" + artifactName,
            size: options.apkSize,
            sha256: options.apkSha256,
        }),
        notesUrl: releaseBase + "/tag/" + options.tag,
    });
}

export async function sha256File(file) {
    const hash = createHash("sha256");
    for await (const chunk of createReadStream(file)) hash.update(chunk);
    return hash.digest("hex");
}

export function sha256Bytes(bytes) {
    return createHash("sha256").update(bytes).digest("hex");
}

export async function writeManagerRelease(options) {
    const sourceApk = path.resolve(options.apk);
    const apkStat = await stat(sourceApk);
    if (!apkStat.isFile()) throw new TypeError("ThunderManager APK input must be a regular file");
    const apkSha256 = await sha256File(sourceApk);
    requireApkMetadata(apkStat.size, apkSha256);

    const outputDirectory = path.resolve(options.outputDirectory);
    await mkdir(outputDirectory, { recursive: true });
    if ((await readdir(outputDirectory)).length !== 0) {
        throw new Error("ThunderManager release output directory must be empty");
    }

    const manifest = createManagerReleaseManifest({
        apkSha256,
        apkSize: apkStat.size,
        managerVersion: options.managerVersion,
        publishedAt: options.publishedAt,
        repository: options.repository,
        tag: options.tag,
    });
    const artifactName = "ThunderManager-" + manifest.version + ".apk";
    const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2) + "\n", "utf8");
    const manifestSha256 = sha256Bytes(manifestBytes);
    const sums = apkSha256 + "  " + artifactName + "\n"
        + manifestSha256 + "  release.json\n";

    await copyFile(sourceApk, path.join(outputDirectory, artifactName), constants.COPYFILE_EXCL);
    await Promise.all([
        writeFile(path.join(outputDirectory, "release.json"), manifestBytes, { flag: "wx" }),
        writeFile(path.join(outputDirectory, "SHA256SUMS"), sums, { encoding: "utf8", flag: "wx" }),
    ]);
    return Object.freeze({
        apkSha256,
        artifactName,
        manifest,
        manifestSha256,
        outputDirectory,
    });
}
