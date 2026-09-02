import { spawnSync } from "node:child_process";

const arguments_ = process.argv.slice(2);
if (arguments_.length === 0 || arguments_.some(argument => !/^[-:A-Za-z0-9_.=]+$/.test(argument))) {
    throw new Error("Gradle arguments are missing or unsafe");
}

const windows = process.platform === "win32";
const result = windows
    ? spawnSync(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", ".\\gradlew.bat", ...arguments_], { stdio: "inherit" })
    : spawnSync("./gradlew", arguments_, { stdio: "inherit" });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
