package dev.thunder.bootstrap;

import java.io.File;
import java.security.PublicKey;

public final class RuntimeSlotSelector {
    private RuntimeSlotSelector() {
    }

    public static Selection select(
        File runtimeRoot,
        String activeSlot,
        int expectedRuntimeContract,
        PublicKey publicKey
    ) throws Exception {
        if (!("a".equals(activeSlot) || "b".equals(activeSlot))) {
            throw new IllegalArgumentException("Active runtime slot is invalid");
        }
        String fallbackSlot = "a".equals(activeSlot) ? "b" : "a";
        Exception activeFailure;
        try {
            return new Selection(
                RuntimeSlotVerifier.verify(new File(runtimeRoot, "slot-" + activeSlot), activeSlot, expectedRuntimeContract, publicKey),
                false
            );
        } catch (Exception error) {
            activeFailure = error;
        }
        try {
            return new Selection(
                RuntimeSlotVerifier.verify(new File(runtimeRoot, "slot-" + fallbackSlot), fallbackSlot, expectedRuntimeContract, publicKey),
                true
            );
        } catch (Exception fallbackFailure) {
            fallbackFailure.addSuppressed(activeFailure);
            throw fallbackFailure;
        }
    }

    public record Selection(RuntimeSlotManifest manifest, boolean usedFallback) {
    }
}
