package dev.thunder.bootstrap;

import java.io.File;

public record RuntimeSlotManifest(
    String slot,
    String runtimeVersion,
    int runtimeContractVersion,
    long runtimeSize,
    String runtimeSha256,
    File runtimeFile
) {
}
