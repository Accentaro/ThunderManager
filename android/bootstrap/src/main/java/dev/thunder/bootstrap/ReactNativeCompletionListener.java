package dev.thunder.bootstrap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ReactNativeCompletionListener implements AutoCloseable {
    private static final String MARKER = "com.facebook.react.bridge.ReactMarker";
    private static final String LISTENER = "com.facebook.react.bridge.ReactMarker$MarkerListener";
    private static final String COMPLETION = "RUN_JS_BUNDLE_END";
    private static final String START = "RUN_JS_BUNDLE_START";

    private final Method removeListener;
    private final Object listener;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ReactNativeCompletionListener(Method removeListener, Object listener) {
        this.removeListener = removeListener;
        this.listener = listener;
    }

    static ReactNativeCompletionListener register(ClassLoader classLoader, String assetName, Runnable completion)
        throws ReflectiveOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(assetName, "assetName");
        Objects.requireNonNull(completion, "completion");
        Class<?> markerClass = Class.forName(MARKER, false, classLoader);
        Class<?> listenerClass = Class.forName(LISTENER, false, classLoader);
        Method add = markerClass.getDeclaredMethod("addListener", listenerClass);
        Method remove = markerClass.getDeclaredMethod("removeListener", listenerClass);
        ReactNativeCompletionListener[] registration = new ReactNativeCompletionListener[1];
        AtomicReference<Integer> startedInstance = new AtomicReference<>();
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
            if (!"logMarker".equals(method.getName()) || arguments == null || arguments.length < 2) return null;
            String marker = arguments[0] instanceof Enum<?> value ? value.name() : String.valueOf(arguments[0]);
            String tag = arguments[1] instanceof String value ? value : null;
            Integer instanceKey = arguments.length > 2 && arguments[2] instanceof Integer value ? value : null;
            boolean exactAsset = assetName.equals(tag) || (tag != null && tag.endsWith("/" + assetName));
            if (START.equals(marker) && exactAsset && instanceKey != null) startedInstance.compareAndSet(null, instanceKey);
            if (COMPLETION.equals(marker) && exactAsset && instanceKey != null && instanceKey.equals(startedInstance.get())) {
                ReactNativeCompletionListener current = registration[0];
                if (current != null && current.closed.compareAndSet(false, true)) {
                    remove.invoke(null, proxy);
                    completion.run();
                }
            }
            return null;
        };
        Object listener = Proxy.newProxyInstance(classLoader, new Class<?>[]{listenerClass}, handler);
        ReactNativeCompletionListener created = new ReactNativeCompletionListener(remove, listener);
        registration[0] = created;
        add.invoke(null, listener);
        return created;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ThunderReactMarkerListener";
            default -> null;
        };
    }

    @Override
    public void close() throws ReflectiveOperationException {
        if (closed.compareAndSet(false, true)) removeListener.invoke(null, listener);
    }
}
