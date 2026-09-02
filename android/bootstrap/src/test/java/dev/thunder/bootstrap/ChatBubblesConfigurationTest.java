package dev.thunder.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ChatBubblesConfigurationTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void radiiAreRoundedBoundedAndDefaulted() {
        assertEquals(13, ChatBubblesConfiguration.boundedRadius(12.6, 4));
        assertEquals(0, ChatBubblesConfiguration.boundedRadius(-4, 4));
        assertEquals(50, ChatBubblesConfiguration.boundedRadius(80, 4));
        assertEquals(50, ChatBubblesConfiguration.boundedRadius(Double.MAX_VALUE, 4));
        assertEquals(4, ChatBubblesConfiguration.boundedRadius("invalid", 4));
    }

    @Test
    public void onlyBooleanTrueEnablesTheNativeRenderer() {
        assertTrue(ChatBubblesConfiguration.isEnabledValue(Boolean.TRUE));
        assertFalse(ChatBubblesConfiguration.isEnabledValue(Boolean.FALSE));
        assertFalse(ChatBubblesConfiguration.isEnabledValue("true"));
        assertFalse(ChatBubblesConfiguration.isEnabledValue(1));
        assertTrue(ChatBubblesConfiguration.isActivated(Boolean.TRUE, Boolean.TRUE));
        assertFalse(ChatBubblesConfiguration.isActivated(Boolean.TRUE, Boolean.FALSE));
        assertFalse(ChatBubblesConfiguration.isActivated(Boolean.FALSE, Boolean.TRUE));
        assertFalse(ChatBubblesConfiguration.isActivated(Boolean.TRUE, "true"));
    }

    @Test
    public void reactNativeHexColorsBecomeAndroidArgb() {
        assertEquals(0xffaabbcc, ChatBubblesConfiguration.parseHexColor("#abc", 0));
        assertEquals(0xddaabbcc, ChatBubblesConfiguration.parseHexColor("#abcd", 0));
        assertEquals(0xff5865f2, ChatBubblesConfiguration.parseHexColor("#5865f2", 0));
        assertEquals(0x805865f2, ChatBubblesConfiguration.parseHexColor("#5865f280", 0));
        assertEquals(0xffaabbcc, ChatBubblesConfiguration.parseHexColor("  #aabbcc  ", 0));
        assertEquals(7, ChatBubblesConfiguration.parseHexColor("invalid", 7));
    }

    @Test
    public void disabledEnabledRecordWinsOverStoredAppearance() {
        ChatBubblesConfiguration configuration = ChatBubblesConfiguration.reconcile(
            false,
            22,
            33,
            "#abcdef",
            "#123456"
        );

        assertSame(ChatBubblesConfiguration.DISABLED, configuration);
        assertFalse(configuration.enabled);
    }

    @Test
    public void enabledAppearanceIsBoundedAndUsesThemeWhenCustomColorIsEmpty() {
        ChatBubblesConfiguration configuration = ChatBubblesConfiguration.reconcile(
            true,
            80,
            -4,
            " ",
            "#5865f280"
        );

        assertTrue(configuration.enabled);
        assertEquals(50, configuration.avatarRadius);
        assertEquals(0, configuration.bubbleRadius);
        assertEquals(0x805865f2, configuration.bubbleColor);
    }

    @Test
    public void customColorOverridesThemeAndInvalidValuesUseDefaults() {
        ChatBubblesConfiguration custom = ChatBubblesConfiguration.reconcile(
            true,
            "invalid",
            Double.POSITIVE_INFINITY,
            "#abc",
            "#123456"
        );

        assertEquals(12, custom.avatarRadius);
        assertEquals(40, custom.bubbleRadius);
        assertEquals(0xffaabbcc, custom.bubbleColor);

        ChatBubblesConfiguration invalidCustom = ChatBubblesConfiguration.reconcile(
            true,
            12,
            40,
            "invalid",
            "#123456"
        );
        assertEquals(0xff123456, invalidCustom.bubbleColor);
    }

    @Test
    public void oversizedAuthoritativeRecordFailsClosed() throws Exception {
        File directory = temporary.newFolder("chatbubbles");
        File enabled = new File(directory, "thunder-plugins-enabled.json");
        try (RandomAccessFile file = new RandomAccessFile(enabled, "rw")) {
            file.setLength(ChatBubblesConfiguration.MAX_RECORD_BYTES + 1L);
        }

        assertSame(ChatBubblesConfiguration.DISABLED, ChatBubblesConfiguration.load(directory));
    }

}
