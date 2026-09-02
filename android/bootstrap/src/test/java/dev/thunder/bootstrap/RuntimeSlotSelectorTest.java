package dev.thunder.bootstrap;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RuntimeSlotSelectorTest {
    @Test
    public void selectsAuthenticatedActiveSlot() throws Exception {
        File root = Files.createTempDirectory("thunder-runtime").toFile();
        KeyPair keyPair = keys();
        writeSlot(root, "a", "runtime-a", keyPair, false);
        RuntimeSlotSelector.Selection selection = RuntimeSlotSelector.select(root, "a", 1, keyPair.getPublic());
        assertEquals("a", selection.manifest().slot());
        assertTrue(!selection.usedFallback());
    }

    @Test
    public void fallsBackWhenActiveSlotIsCorrupt() throws Exception {
        File root = Files.createTempDirectory("thunder-runtime").toFile();
        KeyPair keyPair = keys();
        writeSlot(root, "a", "runtime-a", keyPair, true);
        writeSlot(root, "b", "runtime-b", keyPair, false);
        RuntimeSlotSelector.Selection selection = RuntimeSlotSelector.select(root, "a", 1, keyPair.getPublic());
        assertEquals("b", selection.manifest().slot());
        assertTrue(selection.usedFallback());
    }

    @Test
    public void rejectsBothUnauthenticatedSlots() throws Exception {
        File root = Files.createTempDirectory("thunder-runtime").toFile();
        KeyPair keyPair = keys();
        writeSlot(root, "a", "runtime-a", keyPair, true);
        writeSlot(root, "b", "runtime-b", keyPair, true);
        assertThrows(Exception.class, () -> RuntimeSlotSelector.select(root, "a", 1, keyPair.getPublic()));
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void writeSlot(File root, String slot, String runtime, KeyPair keyPair, boolean corrupt) throws Exception {
        File directory = new File(root, "slot-" + slot);
        assertTrue(directory.mkdirs());
        byte[] runtimeBytes = runtime.getBytes(StandardCharsets.UTF_8);
        Files.write(new File(directory, "runtime.hbc").toPath(), runtimeBytes);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(runtimeBytes));
        Map<String, String> fields = new HashMap<>();
        fields.put("schemaVersion", "1");
        fields.put("slot", slot);
        fields.put("runtimeVersion", "1.0.0");
        fields.put("runtimeContractVersion", "1");
        fields.put("runtimeSize", Integer.toString(runtimeBytes.length));
        fields.put("runtimeSha256", digest);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(RuntimeSlotVerifier.canonical(fields).getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        if (corrupt) signature[0] ^= 1;
        String manifest = RuntimeSlotVerifier.canonical(fields)
            + "signature=" + Base64.getEncoder().encodeToString(signature) + "\n";
        Files.write(new File(directory, "manifest.properties").toPath(), manifest.getBytes(StandardCharsets.UTF_8));
    }
}
