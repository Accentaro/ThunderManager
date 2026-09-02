package com.facebook.react.runtime;

import com.facebook.react.bridge.JSBundleLoader;

public final class ReactInstance {
    private int loadCount;

    private void loadJSBundle(JSBundleLoader loader) {
        if (loader == null) throw new NullPointerException("loader");
        loadCount++;
    }

    public int loadCount() {
        return loadCount;
    }
}
