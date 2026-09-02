import { execFileSync } from "node:child_process";
import { lstatSync, readFileSync, realpathSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

export const MAX_TRACKED_FILE_BYTES = 5 * 1024 * 1024;

const FORBIDDEN_EXTENSIONS = new Set([
    ".aab",
    ".aar",
    ".apk",
    ".apks",
    ".bundle",
    ".class",
    ".dex",
    ".dll",
    ".exe",
    ".idsig",
    ".jks",
    ".key",
    ".keystore",
    ".odex",
    ".p12",
    ".pem",
    ".pfx",
    ".pkcs12",
    ".so",
    ".vdex",
    ".xapk",
]);

const LOCAL_ONLY_PATH_SEGMENTS = new Set([
    ".gradle",
    ".kotlin",
    ".release-repos",
    ".tmp",
    "artifacts",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "private-corpus",
    "release",
    "releases",
    "reports",
    "split-repos",
    "test-artifacts",
]);

const CAPTURE_EXTENSIONS = new Set([
    ".gif",
    ".jpeg",
    ".jpg",
    ".mov",
    ".mp4",
    ".png",
    ".webm",
    ".webp",
]);

const REVIEWED_MEDIA_PATHS = new Set([
    "android/injection-custom/src/main/assets/thunder/backend/brand-icon.png",
    "apps/manager/src/main/res/drawable-nodpi/thunder_brand.png",
    "apps/manager/src/main/res/drawable-nodpi/thunder_cat_console.png",
    "apps/manager/src/main/res/drawable-nodpi/thunder_pixel_cloud.png",
]);

const PRIVATE_KEY_MARKERS = [
    "PRIVATE KEY",
    "ENCRYPTED PRIVATE KEY",
    "RSA PRIVATE KEY",
    "EC PRIVATE KEY",
    "OPENSSH PRIVATE KEY",
].map(label => `-----BEGIN ${label}-----`);

const ABSOLUTE_USER_PATH_PATTERNS = [
    /[A-Za-z]:\\Users\\[^\\\s]+\\/i,
    /\/Users\/[^/\s]+\//,
    /\/home\/[^/\s]+\//,
];

function normalizedRepositoryPath(filePath) {
    return filePath.replaceAll("\\", "/").replace(/^\.\//, "");
}

function samePath(left, right) {
    const normalize = value => {
        const resolved = path.resolve(value);
        return process.platform === "win32" ? resolved.toLowerCase() : resolved;
    };
    return normalize(left) === normalize(right);
}

function formatMiB(bytes) {
    return `${(bytes / 1024 / 1024).toFixed(2)} MiB`;
}

export function inspectTrackedFile({ bytes, filePath, size }) {
    const repositoryPath = normalizedRepositoryPath(filePath);
    const segments = repositoryPath.split("/");
    const basename = segments.at(-1) ?? "";
    const normalizedSegments = segments.map(segment => segment.toLowerCase());
    const normalizedBasename = basename.toLowerCase();
    const extension = path.posix.extname(repositoryPath).toLowerCase();
    const violations = [];

    const localSegmentIndex = normalizedSegments.findIndex(segment => LOCAL_ONLY_PATH_SEGMENTS.has(segment));
    if (localSegmentIndex !== -1) {
        const localSegment = segments[localSegmentIndex];
        violations.push({
            filePath: repositoryPath,
            kind: "local-only-path",
            message: `path segment ${JSON.stringify(localSegment)} is reserved for local/generated material`,
        });
    }

    if (FORBIDDEN_EXTENSIONS.has(extension)) {
        violations.push({
            filePath: repositoryPath,
            kind: "forbidden-extension",
            message: `${extension} files must not be committed`,
        });
    }

    if (CAPTURE_EXTENSIONS.has(extension) && !REVIEWED_MEDIA_PATHS.has(repositoryPath)) {
        violations.push({
            filePath: repositoryPath,
            kind: "unreviewed-media",
            message: "image/video files require an explicit reviewed source-asset allowlist entry",
        });
    }

    if (
        normalizedBasename === "local.properties" ||
        (normalizedBasename.startsWith(".env") && normalizedBasename !== ".env.example")
    ) {
        violations.push({
            filePath: repositoryPath,
            kind: "local-configuration",
            message: `${basename} is local configuration and must not be committed`,
        });
    }

    if (segments.length === 1 && extension === ".xml") {
        violations.push({
            filePath: repositoryPath,
            kind: "root-ui-dump",
            message: "root-level XML is treated as a device UI dump",
        });
    }

    if (size > MAX_TRACKED_FILE_BYTES) {
        violations.push({
            filePath: repositoryPath,
            kind: "oversized",
            message: `${formatMiB(size)} exceeds the ${formatMiB(MAX_TRACKED_FILE_BYTES)} tracked-file limit`,
        });
    }

    if (bytes && PRIVATE_KEY_MARKERS.some(marker => bytes.includes(Buffer.from(marker)))) {
        violations.push({
            filePath: repositoryPath,
            kind: "private-key-material",
            message: "file contains a private-key PEM marker",
        });
    }

    if (bytes && ABSOLUTE_USER_PATH_PATTERNS.some(pattern => pattern.test(bytes.toString("utf8")))) {
        violations.push({
            filePath: repositoryPath,
            kind: "absolute-user-path",
            message: "file contains a machine-specific user-home path",
        });
    }

    return violations;
}

function git(repositoryRoot, arguments_, encoding = "utf8") {
    return execFileSync("git", ["-C", repositoryRoot, ...arguments_], {
        encoding,
        maxBuffer: 16 * 1024 * 1024,
        stdio: ["ignore", "pipe", "pipe"],
    });
}

export function assertIndependentRepositoryRoot(repositoryRoot) {
    const expected = realpathSync(repositoryRoot);
    let discovered;
    try {
        discovered = String(git(expected, ["rev-parse", "--show-toplevel"])).trim();
    } catch {
        throw new Error(`Repository hygiene must run inside an initialized Git repository: ${expected}`);
    }
    const actual = realpathSync(discovered);
    if (!samePath(expected, actual)) {
        throw new Error(
            `Refusing to scan the enclosing repository. Expected Git root ${expected}, but found ${actual}. ` +
            "Initialize and run the check from the intended independent repository root.",
        );
    }
}

export function checkRepository(repositoryRoot) {
    assertIndependentRepositoryRoot(repositoryRoot);
    const trackedOutput = git(repositoryRoot, ["ls-files", "-z", "--cached"], "buffer");
    const trackedPaths = trackedOutput
        .toString("utf8")
        .split("\0")
        .filter(Boolean);
    const violations = [];

    for (const trackedPath of trackedPaths) {
        const normalized = normalizedRepositoryPath(trackedPath);
        const absolutePath = path.resolve(repositoryRoot, ...normalized.split("/"));
        let stat;
        try {
            stat = lstatSync(absolutePath);
        } catch {
            violations.push({
                filePath: normalized,
                kind: "missing-tracked-file",
                message: "tracked path is missing from the working tree",
            });
            continue;
        }
        if (stat.isSymbolicLink()) {
            violations.push({
                filePath: normalized,
                kind: "symbolic-link",
                message: "symbolic links are not accepted in release source trees",
            });
            continue;
        }
        if (!stat.isFile()) {
            violations.push({
                filePath: normalized,
                kind: "non-file-entry",
                message: "tracked entry is not a regular file",
            });
            continue;
        }
        const bytes = stat.size <= MAX_TRACKED_FILE_BYTES ? readFileSync(absolutePath) : undefined;
        violations.push(...inspectTrackedFile({ bytes, filePath: normalized, size: stat.size }));
    }

    return { trackedFileCount: trackedPaths.length, violations };
}

function runCli() {
    const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
    try {
        const result = checkRepository(repositoryRoot);
        if (result.violations.length === 0) {
            process.stdout.write(`Repository hygiene passed (${result.trackedFileCount} tracked files).\n`);
            return;
        }

        process.stderr.write(`Repository hygiene failed with ${result.violations.length} violation(s):\n`);
        for (const violation of result.violations.slice(0, 100)) {
            process.stderr.write(`- [${violation.kind}] ${violation.filePath}: ${violation.message}\n`);
        }
        if (result.violations.length > 100) {
            process.stderr.write(`- ... ${result.violations.length - 100} additional violation(s) omitted\n`);
        }
        process.exitCode = 1;
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        process.stderr.write(`Repository hygiene could not run: ${message}\n`);
        process.exitCode = 2;
    }
}

const invokedPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : undefined;
if (invokedPath === import.meta.url) runCli();
