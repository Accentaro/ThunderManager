import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

const root = path.resolve(import.meta.dirname, "../..");
const workflow = readFileSync(path.join(root, ".github/workflows/verify-manager.yml"), "utf8");
const packageJson = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));

describe("ThunderManager verification workflow policy", () => {
    it("pins every third-party action to a full commit", () => {
        const actions = [...workflow.matchAll(/^\s*uses:\s*([^\s#]+).*$/gm)].map(match => match[1]);
        assert.ok(actions.length >= 4);
        for (const action of actions) assert.match(action, /^[^@\s]+@[0-9a-f]{40}$/);
    });

    it("uses a clean, locked, Manager-only verification path", () => {
        assert.match(workflow, /^permissions:\n  contents: read$/m);
        assert.match(workflow, /persist-credentials: false/);
        assert.match(workflow, /pnpm install --frozen-lockfile/);
        assert.match(workflow, /ANDROID_HOME\/cmdline-tools\/latest\/bin\/sdkmanager/);
        assert.match(workflow, /run: pnpm verify\n/);
        assert.match(workflow, /run: pnpm verify:staging\n/);
        assert.doesNotMatch(workflow, /THUNDER_RUNTIME_FILE|packages\/|build-mobile-runtime|verify:runtime/);
    });

    it("does not expose Thunder source-build scripts in the Manager package", () => {
        assert.equal(packageJson.name, "thunder-manager");
        for (const command of Object.values(packageJson.scripts)) {
            assert.doesNotMatch(command, /packages\/|prototypes\/|build-mobile-runtime|runtime-release/);
        }
    });
});
