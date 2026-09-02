package dev.thunder.bootstrap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class RuntimeSlotVerifier {
    private static final String MANIFEST_FILE = "manifest.properties";
    private static final String RUNTIME_FILE = "runtime.hbc";
    private static final long MAX_MANIFEST_BYTES = 16 * 1024;
    private static final long MAX_RUNTIME_BYTES = 64L * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of(
        "schemaVersion",
        "slot",
        "runtimeVersion",
        "runtimeContractVersion",
        "runtimeSize",
        "runtimeSha256",
        "signature"
    );

    private RuntimeSlotVerifier() {
    }

    static RuntimeSlotManifest verify(
        File directory,
        String expectedSlot,
        int expectedRuntimeContract,
        PublicKey publicKey
    ) throws Exception {
        File manifestFile = new File(directory, MANIFEST_FILE);
        File runtimeFile = new File(directory, RUNTIME_FILE);
        if (!manifestFile.isFile() || manifestFile.length() < 1 || manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw new IOException("Runtime manifest is invalid");
        }
        Map<String, String> fields = parse(manifestFile);
        if (!fields.keySet().equals(FIELDS) || !"1".equals(fields.get("schemaVersion"))) {
            throw new IOException("Runtime manifest schema is invalid");
        }
        if (!expectedSlot.equals(fields.get("slot")) || !("a".equals(expectedSlot) || "b".equals(expectedSlot))) {
            throw new IOException("Runtime slot identity is invalid");
        }

        int contract = parsePositiveInt(fields.get("runtimeContractVersion"));
        long size = parsePositiveLong(fields.get("runtimeSize"));
        if (contract != expectedRuntimeContract || size > MAX_RUNTIME_BYTES) {
            throw new IOException("Runtime contract or size is invalid");
        }
        String expectedDigest = fields.get("runtimeSha256");
        if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime digest is invalid");
        }
        if (!runtimeFile.isFile() || runtimeFile.length() != size || !expectedDigest.equals(sha256(runtimeFile))) {
            throw new IOException("Runtime file does not match its manifest");
        }

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(canonical(fields).getBytes(StandardCharsets.UTF_8));
        byte[] signature = Base64.getDecoder().decode(fields.get("signature"));
        if (!verifier.verify(signature)) throw new IOException("Runtime signature is invalid");

        return new RuntimeSlotManifest(
            expectedSlot,
            required(fields, "runtimeVersion"),
            contract,
            size,
            expectedDigest,
            runtimeFile.getCanonicalFile()
        );
    }

    static String canonical(Map<String, String> fields) throws IOException {
        return "schemaVersion=" + required(fields, "schemaVersion") + "\n"
            + "slot=" + required(fields, "slot") + "\n"
            + "runtimeVersion=" + required(fields, "runtimeVersion") + "\n"
            + "runtimeContractVersion=" + required(fields, "runtimeContractVersion") + "\n"
            + "runtimeSize=" + required(fields, "runtimeSize") + "\n"
            + "runtimeSha256=" + required(fields, "runtimeSha256") + "\n";
    }

    private static Map<String, String> parse(File file) throws IOException {
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IOException("Malformed runtime manifest line");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (value.isBlank() || values.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate or empty runtime manifest field");
            }
        }
        return values;
    }

    private static String required(Map<String, String> fields, String key) throws IOException {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IOException("Missing runtime field");
        return value;
    }

    private static int parsePositiveInt(String value) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("Runtime integer is invalid", error);
        }
    }

    private static long parsePositiveLong(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("Runtime size is invalid", error);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
