package dev.thunder.bootstrap;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.json.JSONObject;

public final class ThunderRuntimeEntrypoint implements EarlyRuntimeEntrypoint {
    private static final String BUNDLE_LOADER = "com.facebook.react.bridge.JSBundleLoader";
    private static final Map<Object, Boolean> ATTACHED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Application application;
    private static volatile BootstrapConfiguration configuration;

    @Override
    public void onApplicationInstantiated(Application hostApplication, ClassLoader hostClassLoader) {
        application = hostApplication;
    }

    void configure(BootstrapConfiguration loaded) {
        configuration = loaded;
    }

    static void attachBeforeHostBundle(Object reactInstance, Method hostLoadMethod) {
        Application hostApplication = application;
        BootstrapConfiguration loaded = configuration;
        if (hostApplication == null || loaded == null) {
            ThunderChatBubblesBridge.shutdown();
            return;
        }
        synchronized (ATTACHED) {
            if (ATTACHED.put(reactInstance, Boolean.TRUE) != null) return;
        }

        RuntimeBootCoordinator.BootSession session;
        try {
            session = RuntimeBootCoordinator.begin(hostApplication);
            String profile = session.effectiveProfile();
            if ("mod-disabled".equals(profile)) {
                ThunderChatBubblesBridge.shutdown();
                BootstrapStatus.recordRuntimeDisabled(profile);
                session.markReady();
                return;
            }
            BootstrapStatus.recordRuntimeLoading(loaded.runtimeAsset, profile);
            ClassLoader classLoader = reactInstance.getClass().getClassLoader();
            Class<?> loaderClass = Class.forName(BUNDLE_LOADER, false, classLoader);
            if (hostLoadMethod.getParameterCount() != 1 || hostLoadMethod.getParameterTypes()[0] != loaderClass) {
                throw new IllegalStateException("React Native loader signature changed");
            }
            verifyRuntimeAsset(hostApplication, loaded);
            ThunderChatBubblesBridge.initialize(hostApplication);
            // Typefaces have to be with the app's font manager before React Native draws anything.
            int fonts = FontRegistrar.registerInstalledFonts(hostApplication, classLoader);
            if (fonts > 0) BootstrapStatus.recordFontsRegistered(fonts);
            String assetName = loaded.runtimeAsset.substring(loaded.runtimeAsset.lastIndexOf('/') + 1);
            ReactNativeCompletionListener completion = ReactNativeCompletionListener.register(
                classLoader,
                assetName,
                () -> {
                    session.markReady();
                    BootstrapStatus.recordRuntimeReady();
                }
            );
            try {
                loadEarlyState(hostApplication, loaderClass, reactInstance, hostLoadMethod);
                Method createAssetLoader = loaderClass.getDeclaredMethod(
                    "createAssetLoader",
                    Context.class,
                    String.class,
                    boolean.class
                );
                Object runtimeLoader = createAssetLoader.invoke(null, hostApplication, loaded.runtimeAsset, false);
                hostLoadMethod.invoke(reactInstance, runtimeLoader);
            } catch (ReflectiveOperationException | RuntimeException error) {
                completion.close();
                throw error;
            }
        } catch (InvocationTargetException error) {
            ThunderChatBubblesBridge.shutdown();
            BootstrapStatus.recordFailure(error.getCause() == null ? error : error.getCause());
        } catch (ReflectiveOperationException | IOException | NoSuchAlgorithmException | RuntimeException error) {
            ThunderChatBubblesBridge.shutdown();
            BootstrapStatus.recordFailure(error);
        }
    }

    /**
     * Seeds the three bounded JSON records the runtime needs before Discord's lazy file-manager
     * module exists. The temporary script is app-private and removed as soon as React Native has
     * evaluated it. Any missing, malformed, oversized, or unsupported seed degrades to the existing
     * late hydration path and never prevents the authenticated runtime from loading.
     */
    private static void loadEarlyState(
        Application hostApplication,
        Class<?> loaderClass,
        Object reactInstance,
        Method hostLoadMethod
    ) {
        File script = null;
        try {
            Map<String, JSONObject> records = new LinkedHashMap<>();
            for (String name : EARLY_STATE_FILES) {
                JSONObject record = readRecord(new File(hostApplication.getFilesDir(), name));
                if (record != null) records.put(name, record);
            }
            Log.i(TAG, "[Thunder] early state records=" + records.size());
            if (records.isEmpty()) return;

            JSONObject state = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : records.entrySet()) {
                state.put(entry.getKey(), entry.getValue());
            }
            byte[] source = ("globalThis.__THUNDER_EARLY_STATE__=" + state + ";\n")
                .getBytes(StandardCharsets.UTF_8);
            if (source.length > MAX_EARLY_STATE_BYTES) return;

            File directory = new File(hostApplication.getCodeCacheDir(), "thunder");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            script = File.createTempFile("early-state-", ".js", directory);
            try (FileOutputStream output = new FileOutputStream(script)) {
                output.write(source);
                output.getFD().sync();
            }
            Method createFileLoader = loaderClass.getDeclaredMethod("createFileLoader", String.class);
            Object loader = createFileLoader.invoke(null, script.getAbsolutePath());
            hostLoadMethod.invoke(reactInstance, loader);
        } catch (Exception ignored) {
            // Compatibility fallback: normal JS hydration remains available after the host loads.
        } finally {
            if (script != null && script.exists()) script.delete();
        }
    }

    private static JSONObject readRecord(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_RECORD_BYTES) return null;
        try (
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())
        ) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RECORD_BYTES) return null;
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) return null;
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void verifyRuntimeAsset(Application hostApplication, BootstrapConfiguration loaded)
        throws IOException, NoSuchAlgorithmException {
        if (!loaded.runtimeAsset.startsWith("assets://") || loaded.runtimeAsset.length() > 160) {
            throw new IOException("Runtime asset URL is invalid");
        }
        String path = loaded.runtimeAsset.substring("assets://".length());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int total = 0;
        try (InputStream input = hostApplication.getAssets().open(path)) {
            byte[] buffer = new byte[16 * 1024];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > MAX_RUNTIME_BYTES) throw new IOException("Runtime asset exceeds its size limit");
                digest.update(buffer, 0, count);
            }
        }
        if (total < 128 || !hex(digest.digest()).equals(loaded.runtimeSha256)) {
            throw new IOException("Runtime asset authentication failed");
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final int MAX_EARLY_STATE_BYTES = 512 * 1024;
    private static final int MAX_RUNTIME_BYTES = 576 * 1024;
    private static final String TAG = "Thunder";
    private static final String[] EARLY_STATE_FILES = {
        "thunder-plugin-settings.json",
        "thunder-appearance.json",
        "thunder-plugins-enabled.json"
    };
}
