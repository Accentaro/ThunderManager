package dev.thunder.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class BootstrapConfiguration {
    private static final String PATH = "assets/thunder/bootstrap.properties";

    final String originalFactory;
    final String runtimeAsset;
    final String runtimeEntrypoint;
    final String runtimeSha256;
    final String runtimeVersion;

    private BootstrapConfiguration(
        String originalFactory,
        String runtimeAsset,
        String runtimeEntrypoint,
        String runtimeSha256,
        String runtimeVersion
    ) {
        this.originalFactory = originalFactory;
        this.runtimeAsset = runtimeAsset;
        this.runtimeEntrypoint = runtimeEntrypoint;
        this.runtimeSha256 = runtimeSha256;
        this.runtimeVersion = runtimeVersion;
    }

    static BootstrapConfiguration load(ClassLoader classLoader) throws IOException {
        try (InputStream input = classLoader.getResourceAsStream(PATH)) {
            if (input == null) throw new IOException("Missing bootstrap configuration");

            Properties properties = new Properties();
            properties.load(input);
            String platform = required(properties, "platform");
            if (!"thunder".equals(platform)) throw new IOException("Unexpected bootstrap platform");

            String schema = required(properties, "schemaVersion");
            if (!"1".equals(schema)) throw new IOException("Unsupported bootstrap schema");

            return new BootstrapConfiguration(
                required(properties, "originalFactory"),
                required(properties, "runtimeAsset"),
                required(properties, "runtimeEntrypoint"),
                required(properties, "runtimeSha256"),
                required(properties, "runtimeVersion")
            );
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing bootstrap field: " + key);
        return value.trim();
    }
}
