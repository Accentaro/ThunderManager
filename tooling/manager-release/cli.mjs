import path from "node:path";
import process from "node:process";

import {
    DEFAULT_MANAGER_RELEASE_REPOSITORY,
    assertManagerReleaseTag,
    readManagerVersion,
    writeManagerRelease,
} from "./release.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const canonicalVersionFile = path.join(root, "apps/manager/version.properties");

function parseOptions(arguments_, allowed) {
    const values = new Map();
    for (let index = 0; index < arguments_.length; index += 2) {
        const name = arguments_[index];
        const value = arguments_[index + 1];
        if (!name || !value || !name.startsWith("--") || !allowed.has(name) || values.has(name)) {
            throw new Error("Unknown, duplicate, or incomplete manager release argument: " + (name ?? "<missing>"));
        }
        values.set(name, value);
    }
    return values;
}

async function checkTag(arguments_) {
    const values = parseOptions(arguments_, new Set(["--tag"]));
    const tag = values.get("--tag");
    if (!tag) throw new Error("Usage: node tooling/manager-release/cli.mjs check-tag --tag vX.Y.Z");
    const version = await readManagerVersion(canonicalVersionFile);
    assertManagerReleaseTag(tag, version);
    process.stdout.write("ThunderManager release identity: " + version.versionName + " (versionCode " + version.versionCode + ")\n");
}

async function packageRelease(arguments_) {
    const values = parseOptions(
        arguments_,
        new Set(["--apk", "--output", "--published-at", "--repository", "--tag"]),
    );
    const apk = values.get("--apk");
    const outputDirectory = values.get("--output");
    const tag = values.get("--tag");
    if (!apk || !outputDirectory || !tag) {
        throw new Error("Usage: node tooling/manager-release/cli.mjs package --tag vX.Y.Z --apk <file> --output <directory> [--published-at <UTC>] [--repository owner/name]");
    }
    const managerVersion = await readManagerVersion(canonicalVersionFile);
    const result = await writeManagerRelease({
        apk,
        managerVersion,
        outputDirectory,
        publishedAt: values.get("--published-at") ?? new Date().toISOString(),
        repository: values.get("--repository") ?? DEFAULT_MANAGER_RELEASE_REPOSITORY,
        tag,
    });
    process.stdout.write(
        "ThunderManager release " + result.manifest.version
            + ": " + result.manifest.apk.size + " bytes, sha256=" + result.apkSha256 + "\n"
            + "Release files: " + result.outputDirectory + "\n",
    );
}

const [command, ...arguments_] = process.argv.slice(2);
if (command === "check-tag") {
    await checkTag(arguments_);
} else if (command === "package") {
    await packageRelease(arguments_);
} else {
    throw new Error("Usage: node tooling/manager-release/cli.mjs <check-tag|package> [options]");
}
