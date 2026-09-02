package dev.thunder.bootstrap;

public final class BootstrapStatus {
    private static volatile String delegatedFactory;
    private static volatile String failureCode;
    private static volatile String runtimeAsset;
    private static volatile String runtimeEntrypoint;
    private static volatile String runtimeProfile;
    private static volatile int registeredFonts;
    private static volatile String runtimeState;

    private BootstrapStatus() {
    }

    static void recordDelegatedFactory(String className) {
        delegatedFactory = className;
    }

    static void recordFailure(Throwable error) {
        failureCode = error.getClass().getSimpleName();
    }

    static void recordRuntimeEntrypoint(String className) {
        runtimeEntrypoint = className;
    }

    static void recordRuntimeLoading(String asset, String profile) {
        runtimeAsset = asset;
        runtimeProfile = profile;
        runtimeState = "loading";
    }

    static void recordRuntimeReady() {
        runtimeState = "ready";
    }

    /** How many installed typefaces the app accepted this launch, for the boot record. */
    static void recordFontsRegistered(int count) {
        registeredFonts = count;
    }

    static int registeredFonts() {
        return registeredFonts;
    }

    static void recordRuntimeDisabled(String profile) {
        runtimeProfile = profile;
        runtimeState = "disabled";
    }

    public static String delegatedFactory() {
        return delegatedFactory;
    }

    public static String failureCode() {
        return failureCode;
    }

    public static String runtimeEntrypoint() {
        return runtimeEntrypoint;
    }

    public static String runtimeAsset() {
        return runtimeAsset;
    }

    public static String runtimeProfile() {
        return runtimeProfile;
    }

    public static String runtimeState() {
        return runtimeState;
    }
}
