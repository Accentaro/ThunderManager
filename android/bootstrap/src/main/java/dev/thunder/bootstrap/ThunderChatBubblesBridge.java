package dev.thunder.bootstrap;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public final class ThunderChatBubblesBridge {
    private static volatile Application application;
    private static volatile ChatBubblesController controller;
    private static boolean installRetryScheduled;
    private static int installRetryCount;
    private static long lastFailureLog = -10_000;
    private static int suppressedFailures;
    private static final Runnable INSTALL = ThunderChatBubblesBridge::installIfPossible;
    private static final Runnable INSTALL_RETRY = () -> {
        synchronized (ThunderChatBubblesBridge.class) {
            if (!installRetryScheduled) return;
            installRetryScheduled = false;
        }
        installIfPossible();
    };

    private ThunderChatBubblesBridge() {}

    static void initialize(Application hostApplication) {
        try {
            synchronized (ThunderChatBubblesBridge.class) {
                application = hostApplication;
            }
            mainHandler().post(INSTALL);
        } catch (Throwable error) {
            shutdown();
            reportFailure("initialization dispatch", error);
        }
    }

    static void shutdown() {
        ChatBubblesController current;
        boolean hadPendingWork;
        synchronized (ThunderChatBubblesBridge.class) {
            hadPendingWork = application != null || installRetryScheduled;
            application = null;
            current = controller;
            controller = null;
            installRetryCount = 0;
            installRetryScheduled = false;
        }
        if (current != null) current.deactivate();
        if (current == null && !hadPendingWork) return;
        try {
            Handler mainHandler = mainHandler();
            mainHandler.removeCallbacks(INSTALL);
            mainHandler.removeCallbacks(INSTALL_RETRY);
            if (current == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                current.shutdown();
            } else {
                mainHandler.postAtFrontOfQueue(current::shutdown);
            }
        } catch (Throwable error) {
            reportFailure("shutdown", error);
        }
    }

    public static void thunderBeforeConfigureAccessoriesMargin(Object receiver) {
        dispatch(receiver, Callback.BEFORE_ACCESSORIES);
    }

    public static void thunderAfterConfigureAccessoriesMargin(Object receiver) {
        dispatch(receiver, Callback.AFTER_ACCESSORIES);
    }

    public static void thunderBeforeConfigureAuthor(Object receiver) {
        dispatch(receiver, Callback.BEFORE_AUTHOR);
    }

    public static void thunderAfterConfigureAuthor(Object receiver) {
        dispatch(receiver, Callback.AFTER_AUTHOR);
    }

    public static void beforeConfigureAccessoriesMargin(Object receiver) {
        thunderBeforeConfigureAccessoriesMargin(receiver);
    }

    public static void afterConfigureAccessoriesMargin(Object receiver) {
        thunderAfterConfigureAccessoriesMargin(receiver);
    }

    public static void beforeConfigureAuthor(Object receiver) {
        thunderBeforeConfigureAuthor(receiver);
    }

    public static void afterConfigureAuthor(Object receiver) {
        thunderAfterConfigureAuthor(receiver);
    }

    private static void dispatch(Object receiver, Callback callback) {
        if (receiver == null) return;
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                reportFailure("callback arrived off the main thread", null);
                return;
            }
            installIfPossible();
            ChatBubblesController current = controller;
            if (current == null) return;
            switch (callback) {
                case BEFORE_ACCESSORIES -> current.beforeConfigureAccessoriesMargin(receiver);
                case AFTER_ACCESSORIES -> current.afterConfigureAccessoriesMargin(receiver);
                case BEFORE_AUTHOR -> current.beforeConfigureAuthor(receiver);
                case AFTER_AUTHOR -> current.afterConfigureAuthor(receiver);
            }
        } catch (Throwable error) {
            try {
                ChatBubblesController current = controller;
                if (current != null) {
                    current.reportFailure("callback", error);
                } else {
                    reportFailure("callback", error);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installIfPossible() {
        synchronized (ThunderChatBubblesBridge.class) {
            if (controller != null) return;
            Application currentApplication = application;
            if (currentApplication == null) return;
            try {
                controller = ChatBubblesController.install(currentApplication);
                installRetryCount = 0;
                installRetryScheduled = false;
            } catch (Throwable error) {
                reportFailure("initialization", error);
                scheduleInstallRetry();
            }
        }
    }

    private static void scheduleInstallRetry() {
        if (installRetryScheduled || installRetryCount >= MAX_INSTALL_RETRIES) return;
        installRetryScheduled = true;
        installRetryCount++;
        try {
            mainHandler().postDelayed(INSTALL_RETRY, INSTALL_RETRY_MS);
        } catch (Throwable ignored) {
            installRetryScheduled = false;
        }
    }

    private static synchronized void reportFailure(String operation, Throwable error) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFailureLog < FAILURE_LOG_INTERVAL_MS) {
                suppressedFailures++;
                return;
            }
            String suffix = suppressedFailures == 0 ? "" : " (" + suppressedFailures + " suppressed)";
            suppressedFailures = 0;
            lastFailureLog = now;
            String message = "[Thunder] ChatBubbles " + operation + " contained" + suffix;
            if (error == null) {
                Log.w(TAG, message);
            } else {
                Log.w(TAG, message, error);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Handler mainHandler() {
        return MainHandlerHolder.INSTANCE;
    }

    private static final class MainHandlerHolder {
        static final Handler INSTANCE = new Handler(Looper.getMainLooper());
    }

    private enum Callback {
        BEFORE_ACCESSORIES,
        AFTER_ACCESSORIES,
        BEFORE_AUTHOR,
        AFTER_AUTHOR
    }

    private static final int MAX_INSTALL_RETRIES = 8;
    private static final long INSTALL_RETRY_MS = 100;
    private static final long FAILURE_LOG_INTERVAL_MS = 10_000;
    private static final String TAG = "Thunder";
}
