package dev.thunder.bootstrap;

import com.facebook.react.bridge.ReactMarker;
import com.facebook.react.bridge.ReactMarkerConstants;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public final class ReactNativeCompletionListenerTest {
    @Test
    public void completesOnlyAfterTheExactThunderBundleEnds() throws Exception {
        AtomicInteger completions = new AtomicInteger();
        try (ReactNativeCompletionListener ignored = ReactNativeCompletionListener.register(
            getClass().getClassLoader(),
            "runtime.js",
            completions::incrementAndGet
        )) {
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_END, "/index.android.bundle");
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_END, "/runtime.js", 7);
            assertEquals(0, completions.get());
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_START, "/runtime.js", 7);
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_END, "/runtime.js", 8);
            assertEquals(0, completions.get());
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_END, "/runtime.js", 7);
            ReactMarker.emit(ReactMarkerConstants.RUN_JS_BUNDLE_END, "/runtime.js", 7);
            assertEquals(1, completions.get());
        }
    }
}
