import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import {
    MAX_TRACKED_FILE_BYTES,
    assertIndependentRepositoryRoot,
    checkRepository,
    inspectTrackedFile,
} from "../check-repository-hygiene.mjs";

function git(repositoryRoot, ...arguments_) {
    execFileSync("git", ["-C", repositoryRoot, ...arguments_], { stdio: "ignore" });
}

async function temporaryRepository() {
    const repositoryRoot = await mkdtemp(path.join(tmpdir(), "thunder-hygiene-"));
    git(repositoryRoot, "init", "--quiet");
    return repositoryRoot;
}

describe("repository hygiene policy", () => {
    it("rejects release packages, local artifacts, unreviewed captures, and large files", () => {
        const packageKinds = inspectTrackedFile({ filePath: "release/Thunder.apk", size: 1 })
            .map(violation => violation.kind);
        assert(packageKinds.includes("forbidden-extension"));
        assert(packageKinds.includes("local-only-path"));

        assert.deepEqual(
            inspectTrackedFile({ filePath: "proof.png", size: 1 }).map(violation => violation.kind),
            ["unreviewed-media"],
        );
        assert.deepEqual(
            inspectTrackedFile({
                filePath: "apps/manager/src/main/res/drawable-nodpi/thunder_brand.png",
                size: 1,
            }),
            [],
        );
        assert(inspectTrackedFile({ filePath: "large.txt", size: MAX_TRACKED_FILE_BYTES + 1 })
            .some(violation => violation.kind === "oversized"));
    });

    it("detects private key material without echoing it", () => {
        const privateKeyHeader = ["-----BEGIN", "PRIVATE KEY-----"].join(" ");
        const violations = inspectTrackedFile({
            bytes: Buffer.from(`${privateKeyHeader}\nredacted\n-----END PRIVATE KEY-----`),
            filePath: "credential",
            size: 72,
        });
        assert.equal(violations.some(violation => violation.kind === "private-key-material"), true);
        assert.equal(JSON.stringify(violations).includes("redacted"), false);
    });

    it("detects machine-specific user paths", () => {
        const localPath = ["C:", "Users", "person", "Android", "Sdk"].join("\\");
        const violations = inspectTrackedFile({
            bytes: Buffer.from(localPath),
            filePath: "runbook.md",
            size: localPath.length,
        });
        assert.equal(violations.some(violation => violation.kind === "absolute-user-path"), true);
    });

    it("checks the staged/tracked file set in an independent repository", async () => {
        const repositoryRoot = await temporaryRepository();
        try {
            await writeFile(path.join(repositoryRoot, "README.md"), "safe\n");
            await writeFile(path.join(repositoryRoot, "forbidden.apk"), "not really an apk\n");
            git(repositoryRoot, "add", "README.md", "forbidden.apk");

            const result = checkRepository(repositoryRoot);
            assert.equal(result.trackedFileCount, 2);
            assert.equal(result.violations.some(violation => violation.filePath === "forbidden.apk"), true);
            assert.equal(result.violations.some(violation => violation.filePath === "README.md"), false);
        } finally {
            await rm(repositoryRoot, { force: true, recursive: true });
        }
    });

    it("refuses to scan a child directory through an enclosing Git repository", async () => {
        const repositoryRoot = await temporaryRepository();
        try {
            const child = path.join(repositoryRoot, "child");
            await mkdir(child);
            assert.throws(
                () => assertIndependentRepositoryRoot(child),
                /Refusing to scan the enclosing repository/,
            );
        } finally {
            await rm(repositoryRoot, { force: true, recursive: true });
        }
    });
});
