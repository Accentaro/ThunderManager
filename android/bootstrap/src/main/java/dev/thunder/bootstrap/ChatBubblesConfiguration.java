package dev.thunder.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.json.JSONObject;

final class ChatBubblesConfiguration {
    static final ChatBubblesConfiguration DISABLED = new ChatBubblesConfiguration(false, 12, 40, 0x66000000);
    static final int MAX_RECORD_BYTES = 256 * 1024;

    final boolean enabled;
    final int avatarRadius;
    final int bubbleRadius;
    final int bubbleColor;

    private ChatBubblesConfiguration(boolean enabled, int avatarRadius, int bubbleRadius, int bubbleColor) {
        this.enabled = enabled;
        this.avatarRadius = avatarRadius;
        this.bubbleRadius = bubbleRadius;
        this.bubbleColor = bubbleColor;
    }

    static ChatBubblesConfiguration load(File directory) {
        try {
            JSONObject enabled = read(new File(directory, "thunder-plugins-enabled.json"));
            JSONObject theme = read(new File(directory, "thunder-chatbubbles-theme.json"));
            boolean activated = isActivated(
                enabled == null ? null : enabled.opt("chatbubbles"),
                theme == null ? null : theme.opt("active")
            );
            if (!activated) return DISABLED;

            JSONObject settingsRoot = read(new File(directory, "thunder-plugin-settings.json"));
            JSONObject settings = settingsRoot == null ? null : settingsRoot.optJSONObject("chatbubbles");
            return reconcile(
                true,
                settings == null ? null : settings.opt("avatarRadius"),
                settings == null ? null : settings.opt("bubbleChatRadius"),
                settings == null ? null : settings.opt("bubbleChatColor"),
                theme == null ? null : theme.opt("bubbleColor")
            );
        } catch (RuntimeException ignored) {
            return DISABLED;
        }
    }

    static ChatBubblesConfiguration reconcile(
        boolean pluginEnabled,
        Object avatarRadius,
        Object bubbleRadius,
        Object customColor,
        Object themeColor
    ) {
        if (!pluginEnabled) return DISABLED;
        int color = parseHexColor(
            colorText(customColor),
            parseHexColor(colorText(themeColor), 0x66000000)
        );
        return new ChatBubblesConfiguration(
            true,
            boundedRadius(avatarRadius, 12),
            boundedRadius(bubbleRadius, 40),
            color
        );
    }

    static int boundedRadius(Object value, int fallback) {
        double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else if (value instanceof String text) {
            try {
                number = Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        } else {
            return fallback;
        }
        if (!Double.isFinite(number)) return fallback;
        return (int) Math.round(Math.max(0, Math.min(50, number)));
    }

    static boolean isEnabledValue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    static boolean isActivated(Object pluginEnabled, Object runtimeActive) {
        return isEnabledValue(pluginEnabled) && isEnabledValue(runtimeActive);
    }

    /** React Native reads eight hex digits as RRGGBBAA, then hands Android an ARGB integer. */
    static int parseHexColor(String value, int fallback) {
        if (value == null) return fallback;
        String normalized = value.trim();
        if (!normalized.startsWith("#")) return fallback;
        String hex = normalized.substring(1);
        try {
            return switch (hex.length()) {
                case 3 -> 0xff000000
                    | Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16) << 16
                    | Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16) << 8
                    | Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16);
                case 4 -> Integer.parseInt("" + hex.charAt(3) + hex.charAt(3), 16) << 24
                    | Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16) << 16
                    | Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16) << 8
                    | Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16);
                case 6 -> 0xff000000 | Integer.parseInt(hex, 16);
                case 8 -> Integer.parseInt(hex.substring(6), 16) << 24
                    | Integer.parseInt(hex.substring(0, 6), 16);
                default -> fallback;
            };
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static JSONObject read(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_RECORD_BYTES) return null;
        try (
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())
        ) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RECORD_BYTES) return null;
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String colorText(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChatBubblesConfiguration configuration)) return false;
        return enabled == configuration.enabled
            && avatarRadius == configuration.avatarRadius
            && bubbleRadius == configuration.bubbleRadius
            && bubbleColor == configuration.bubbleColor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, avatarRadius, bubbleRadius, bubbleColor);
    }
}
