package dev.thunder.bootstrap;

import android.app.Application;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the typefaces the reader installed, before React Native draws anything with them.
 *
 * Android will not take a new typeface from JavaScript: React Native resolves a font family through
 * its own font manager, which is a native object with no bridge of its own. The runtime downloads a
 * font and writes down which of Discord's families each file provides; this reads that record while
 * the app is starting and hands the files to the font manager, which is the one moment the app will
 * accept them. Nothing here fails the launch: a font that cannot be read is skipped.
 */
final class FontRegistrar {
    private static final String INDEX_FILE = "thunder-fonts.json";
    private static final String FONT_MANAGER = "com.facebook.react.common.assets.ReactFontManager";
    private static final String LEGACY_FONT_MANAGER = "com.facebook.react.views.text.ReactFontManager";
    private static final int MAX_INDEX_BYTES = 256 * 1024;
    // A font that does not register fails silently by nature — nothing draws differently and nothing
    // throws — so the outcome is written to the log, where the rest of a launch can be read.
    private static final String TAG = "ThunderFonts";

    private FontRegistrar() {
    }

    /** Registers every installed family. Returns how many were accepted, for the boot record. */
    static int registerInstalledFonts(Application application, ClassLoader hostClassLoader) {
        Map<String, String> families;
        try {
            families = readIndex(new File(application.getFilesDir(), INDEX_FILE));
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "could not read the installed fonts", error);
            return 0;
        }
        if (families.isEmpty()) return 0;

        int registered = 0;
        for (String className : new String[] { FONT_MANAGER, LEGACY_FONT_MANAGER }) {
            registered = Math.max(registered, registerWith(className, hostClassLoader, families));
        }
        Log.i(TAG, "registered " + registered + " of " + families.size() + " families");
        return registered;
    }

    /**
     * Registers every family with one font manager.
     *
     * A build can carry both the current and the legacy manager, and which one its text views ask is
     * not something the bootstrap can see — so both are given the fonts, and a class that is not
     * there is simply skipped.
     */
    private static int registerWith(
        String className,
        ClassLoader hostClassLoader,
        Map<String, String> families
    ) {
        Object manager;
        Method addCustomFont;
        try {
            Class<?> managerClass = Class.forName(className, false, hostClassLoader);
            Method getInstance = managerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            manager = getInstance.invoke(null);
            addCustomFont = managerClass.getDeclaredMethod("addCustomFont", String.class, Typeface.class);
            addCustomFont.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return 0;
        }

        int registered = 0;
        for (Map.Entry<String, String> entry : families.entrySet()) {
            try {
                File file = new File(entry.getValue());
                if (!file.isFile() || !file.canRead()) continue;
                Typeface typeface = Typeface.createFromFile(file);
                if (typeface == null) continue;
                addCustomFont.invoke(manager, entry.getKey(), typeface);
                registered++;
            } catch (ReflectiveOperationException | RuntimeException error) {
                // One unreadable font must not cost the others their registration.
                Log.w(TAG, "could not register " + entry.getKey() + " with " + className, error);
            }
        }
        Log.i(TAG, className + " took " + registered + " families");
        return registered;
    }

    /**
     * Reads the record the runtime writes: `{"families":{"ggsans-Normal":"/path/file.ttf"},...}`.
     *
     * Only the chosen font is under that key, and it is keyed by the family names the app's own text
     * styles ask for — which the runtime noted while the app was running, because the app resolves
     * those names itself and will only draw a typeface registered under exactly what it asks for.
     *
     * Parsed by hand rather than with a JSON library, because the bootstrap carries no dependencies
     * and this file is written by Thunder itself, in one shape.
     */
    private static Map<String, String> readIndex(File index) throws IOException {
        Map<String, String> families = new LinkedHashMap<>();
        if (!index.isFile() || index.length() > MAX_INDEX_BYTES) return families;

        byte[] buffer = new byte[(int) index.length()];
        try (InputStream input = new FileInputStream(index)) {
            int read = 0;
            while (read < buffer.length) {
                int count = input.read(buffer, read, buffer.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        String contents = new String(buffer, StandardCharsets.UTF_8);

        int cursor = 0;
        while (true) {
            int marker = contents.indexOf("\"families\"", cursor);
            if (marker < 0) break;
            int open = contents.indexOf('{', marker);
            int close = open < 0 ? -1 : contents.indexOf('}', open);
            if (open < 0 || close < 0) break;
            readPairs(contents.substring(open + 1, close), families);
            cursor = close + 1;
        }
        return families;
    }

    private static void readPairs(String body, Map<String, String> families) {
        int cursor = 0;
        while (true) {
            int keyStart = body.indexOf('"', cursor);
            if (keyStart < 0) return;
            int keyEnd = body.indexOf('"', keyStart + 1);
            int valueStart = keyEnd < 0 ? -1 : body.indexOf('"', body.indexOf(':', keyEnd) + 1);
            int valueEnd = valueStart < 0 ? -1 : body.indexOf('"', valueStart + 1);
            if (keyEnd < 0 || valueStart < 0 || valueEnd < 0) return;

            String family = body.substring(keyStart + 1, keyEnd);
            String path = body.substring(valueStart + 1, valueEnd).replace("\\/", "/");
            if (!family.isEmpty() && !path.isEmpty()) families.put(family, path);
            cursor = valueEnd + 1;
        }
    }
}
