package dev.thunder.bootstrap;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

public final class ThunderAppComponentFactory extends AppComponentFactory {
    private volatile AppComponentFactory delegate;
    private volatile BootstrapConfiguration configuration;

    private AppComponentFactory delegate(ClassLoader classLoader) {
        AppComponentFactory current = delegate;
        if (current != null) return current;

        synchronized (this) {
            current = delegate;
            if (current != null) return current;

            try {
                BootstrapConfiguration loaded = BootstrapConfiguration.load(classLoader);
                if (getClass().getName().equals(loaded.originalFactory)) {
                    throw new IllegalStateException("Bootstrap factory cannot delegate to itself");
                }
                Object instance = classLoader.loadClass(loaded.originalFactory)
                    .getDeclaredConstructor()
                    .newInstance();
                if (!(instance instanceof AppComponentFactory factory)) {
                    throw new IllegalStateException("Original factory has the wrong type");
                }
                configuration = loaded;
                delegate = factory;
                BootstrapStatus.recordDelegatedFactory(loaded.originalFactory);
                return factory;
            } catch (ReflectiveOperationException | RuntimeException | java.io.IOException error) {
                BootstrapStatus.recordFailure(error);
                AppComponentFactory fallback = new AppComponentFactory();
                delegate = fallback;
                return fallback;
            }
        }
    }

    private void attachRuntime(Application application, ClassLoader classLoader) {
        BootstrapConfiguration loaded = configuration;
        if (loaded == null || loaded.runtimeEntrypoint.isEmpty()) return;

        try {
            Object instance = classLoader.loadClass(loaded.runtimeEntrypoint)
                .getDeclaredConstructor()
                .newInstance();
            if (!(instance instanceof EarlyRuntimeEntrypoint entrypoint)) {
                throw new IllegalStateException("Runtime entrypoint has the wrong type");
            }
            entrypoint.onApplicationInstantiated(application, classLoader);
            if (entrypoint instanceof ThunderRuntimeEntrypoint runtime) {
                runtime.configure(loaded);
            }
            BootstrapStatus.recordRuntimeEntrypoint(loaded.runtimeEntrypoint);
        } catch (ReflectiveOperationException | RuntimeException error) {
            BootstrapStatus.recordFailure(error);
        } catch (Exception error) {
            BootstrapStatus.recordFailure(error);
        }
    }

    @TargetApi(29)
    @Override
    public ClassLoader instantiateClassLoader(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        return delegate(classLoader).instantiateClassLoader(classLoader, applicationInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader classLoader, String className)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        Application application = delegate(classLoader).instantiateApplication(classLoader, className);
        attachRuntime(application, classLoader);
        return application;
    }

    @Override
    public Activity instantiateActivity(ClassLoader classLoader, String className, Intent intent)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate(classLoader).instantiateActivity(classLoader, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader classLoader, String className)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate(classLoader).instantiateProvider(classLoader, className);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader classLoader, String className, Intent intent)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate(classLoader).instantiateReceiver(classLoader, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader classLoader, String className, Intent intent)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return delegate(classLoader).instantiateService(classLoader, className, intent);
    }
}
