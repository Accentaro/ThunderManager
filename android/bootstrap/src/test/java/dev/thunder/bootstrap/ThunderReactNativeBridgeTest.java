package dev.thunder.bootstrap;

import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.runtime.ReactInstance;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ThunderReactNativeBridgeTest {
    @Test
    public void delegatesExactlyOnceToThePinnedHostMethod() {
        ReactInstance instance = new ReactInstance();
        ThunderReactNativeBridge.loadJSBundle(instance, new JSBundleLoader());
        assertEquals(1, instance.loadCount());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAnUnexpectedReceiver() {
        ThunderReactNativeBridge.loadJSBundle(new Object(), new JSBundleLoader());
    }
}
