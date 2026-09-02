package dev.thunder.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Length-preserving DEX seam that attaches Thunder before delegating the host bundle load. */
public final class ThunderReactNativeBridge {
    private static final String REACT_INSTANCE = "com.facebook.react.runtime.ReactInstance";
    private static final String BUNDLE_LOADER = "com.facebook.react.bridge.JSBundleLoader";
    private static volatile Method hostLoadMethod;

    private ThunderReactNativeBridge() {
    }

    public static void loadJSBundle(Object reactInstance, Object bundleLoader) {
        Objects.requireNonNull(reactInstance, "reactInstance");
        Objects.requireNonNull(bundleLoader, "bundleLoader");
        try {
            Method method = resolveHostLoadMethod(reactInstance, bundleLoader);
            ThunderRuntimeEntrypoint.attachBeforeHostBundle(reactInstance, method);
            method.invoke(reactInstance, bundleLoader);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("React Native bundle load failed", cause);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("React Native bundle seam failed closed", error);
        }
    }

    private static Method resolveHostLoadMethod(Object reactInstance, Object bundleLoader)
        throws ReflectiveOperationException {
        Method cached = hostLoadMethod;
        if (cached != null) return cached;
        synchronized (ThunderReactNativeBridge.class) {
            cached = hostLoadMethod;
            if (cached != null) return cached;
            Class<?> instanceClass = reactInstance.getClass();
            if (!REACT_INSTANCE.equals(instanceClass.getName())) {
                throw new NoSuchMethodException("Unexpected ReactInstance implementation");
            }
            ClassLoader classLoader = instanceClass.getClassLoader();
            Class<?> loaderClass = Class.forName(BUNDLE_LOADER, false, classLoader);
            if (!loaderClass.isInstance(bundleLoader)) {
                throw new NoSuchMethodException("Unexpected JSBundleLoader implementation");
            }
            Method resolved = instanceClass.getDeclaredMethod("loadJSBundle", loaderClass);
            resolved.setAccessible(true);
            hostLoadMethod = resolved;
            return resolved;
        }
    }
}
