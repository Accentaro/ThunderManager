package dev.thunder.bootstrap;

import android.app.Application;

/**
 * Backend-owned early runtime seam. Implementations must return quickly and must not assume that
 * the Application base context has already been attached.
 */
public interface EarlyRuntimeEntrypoint {
    void onApplicationInstantiated(Application application, ClassLoader hostClassLoader) throws Exception;
}
