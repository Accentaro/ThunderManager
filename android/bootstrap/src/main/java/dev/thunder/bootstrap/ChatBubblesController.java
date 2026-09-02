package dev.thunder.bootstrap;

import android.app.Application;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ChatBubblesController {
    private final File directory;
    private final Handler mainHandler;
    private final ChatBubblesRenderer renderer;
    private final Map<ViewGroup, HookPhaseState> hookPhases = new WeakHashMap<>();
    private final Runnable reload = this::reloadConfigurationSafely;
    private ChatBubblesConfiguration configuration = ChatBubblesConfiguration.DISABLED;
    private FileObserver observer;
    private boolean reloadPending;
    private long reloadPendingSince;
    private long lastConfigurationRead;
    private long lastFailureLog = -10_000;
    private int suppressedFailures;
    private volatile boolean closed;

    private ChatBubblesController(Application application) {
        directory = application.getFilesDir();
        mainHandler = new Handler(Looper.getMainLooper());
        renderer = new ChatBubblesRenderer(
            application.getResources().getDisplayMetrics().density,
            this::reportFailure
        );
    }

    static ChatBubblesController install(Application application) {
        ChatBubblesController controller = new ChatBubblesController(application);
        controller.reloadConfigurationSafely();
        controller.startWatching();
        Log.i(TAG, "[Thunder] ChatBubbles native renderer ready");
        return controller;
    }

    void beforeConfigureAccessoriesMargin(Object receiver) {
        if (closed) return;
        ViewGroup message = receiver(receiver);
        if (message == null) return;
        maybePollConfiguration();
        if (!configuration.enabled) {
            hookPhases.remove(message);
            return;
        }
        renderer.remember(message);
        renderer.restoreReceiver(message);
        hookPhases.put(message, new HookPhaseState(HookPhase.ACCESSORIES_STARTED, SystemClock.uptimeMillis()));
    }

    void afterConfigureAccessoriesMargin(Object receiver) {
        if (closed) return;
        ViewGroup message = receiver(receiver);
        if (message == null) return;
        if (!configuration.enabled) {
            hookPhases.remove(message);
            return;
        }
        renderer.remember(message);
        HookPhaseState phase = hookPhases.get(message);
        renderer.adjustAccessoryMargins(message);
        if (phase != null && phase.phase == HookPhase.ACCESSORIES_STARTED) {
            hookPhases.put(
                message,
                new HookPhaseState(HookPhase.ACCESSORIES_COMPLETE, SystemClock.uptimeMillis())
            );
        } else {
            hookPhases.remove(message);
        }
    }

    void beforeConfigureAuthor(Object receiver) {
        if (closed) return;
        ViewGroup message = receiver(receiver);
        if (message == null) return;
        maybePollConfiguration();
        if (!configuration.enabled) {
            hookPhases.remove(message);
            return;
        }
        renderer.remember(message);
        HookPhaseState phase = hookPhases.remove(message);
        long now = SystemClock.uptimeMillis();
        boolean followsAccessories = phase != null
            && phase.phase == HookPhase.ACCESSORIES_COMPLETE
            && now - phase.at <= HOOK_PHASE_WINDOW_MS;
        if (!followsAccessories) renderer.restoreReceiver(message);
    }

    void afterConfigureAuthor(Object receiver) {
        if (closed) return;
        ViewGroup message = receiver(receiver);
        if (message == null) return;
        hookPhases.remove(message);
        if (!configuration.enabled) return;
        renderer.remember(message);
        renderer.adjustAccessoryMargins(message);
        renderer.styleAuthor(message, configuration);
    }

    synchronized void reportFailure(String operation, Throwable error) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFailureLog < FAILURE_LOG_INTERVAL_MS) {
                suppressedFailures++;
                return;
            }
            String suffix = suppressedFailures == 0 ? "" : " (" + suppressedFailures + " suppressed)";
            suppressedFailures = 0;
            lastFailureLog = now;
            Log.w(TAG, "[Thunder] ChatBubbles " + operation + " contained" + suffix, error);
        } catch (Throwable ignored) {
        }
    }

    void deactivate() {
        closed = true;
    }

    void shutdown() {
        closed = true;
        try {
            mainHandler.removeCallbacks(reload);
        } catch (Throwable error) {
            reportFailure("shutdown callback removal", error);
        }
        FileObserver currentObserver = observer;
        observer = null;
        try {
            if (currentObserver != null) currentObserver.stopWatching();
        } catch (Throwable error) {
            reportFailure("shutdown observer", error);
        }
        reloadPending = false;
        reloadPendingSince = 0;
        hookPhases.clear();
        configuration = ChatBubblesConfiguration.DISABLED;
        try {
            renderer.restoreAll();
        } catch (Throwable error) {
            reportFailure("shutdown restoration", error);
        }
        try {
            Log.i(TAG, "[Thunder] ChatBubbles native renderer shut down");
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void startWatching() {
        if (closed) return;
        try {
            observer = new FileObserver(directory.getAbsolutePath(), WATCH_EVENTS) {
                @Override
                public void onEvent(int event, String path) {
                    if (closed || (event & WATCH_EVENTS) == 0 || path == null || !WATCHED_FILES.contains(path)) return;
                    try {
                        mainHandler.post(ChatBubblesController.this::scheduleReload);
                    } catch (Throwable error) {
                        reportFailure("configuration observer dispatch", error);
                    }
                }
            };
            observer.startWatching();
        } catch (Throwable error) {
            observer = null;
            reportFailure("configuration observer", error);
        }
    }

    private void scheduleReload() {
        if (closed) return;
        long now = SystemClock.uptimeMillis();
        if (!reloadPending) {
            reloadPending = true;
            reloadPendingSince = now;
        }
        mainHandler.removeCallbacks(reload);
        long maximumRemaining = Math.max(0, reloadPendingSince + MAX_DEBOUNCE_MS - now);
        mainHandler.postDelayed(reload, Math.min(DEBOUNCE_MS, maximumRemaining));
    }

    private void reloadConfiguration() {
        if (closed) return;
        reloadPending = false;
        reloadPendingSince = 0;
        lastConfigurationRead = SystemClock.uptimeMillis();
        ChatBubblesConfiguration next = ChatBubblesConfiguration.load(directory);
        if (next.equals(configuration)) return;

        configuration = next;
        hookPhases.clear();
        renderer.restoreAll();
        if (next.enabled) renderer.restyleKnown(next);
        Log.i(
            TAG,
            next.enabled
                ? "[Thunder] ChatBubbles native renderer configured"
                : "[Thunder] ChatBubbles native renderer disabled"
        );
    }

    private void reloadConfigurationSafely() {
        if (closed) return;
        try {
            reloadConfiguration();
        } catch (Throwable error) {
            reportFailure("configuration reload", error);
        }
    }

    private void maybePollConfiguration() {
        if (closed) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastConfigurationRead < FALLBACK_POLL_MS) return;
        reloadConfigurationSafely();
    }

    private static ViewGroup receiver(Object value) {
        return value instanceof ViewGroup group ? group : null;
    }

    private enum HookPhase {
        ACCESSORIES_STARTED,
        ACCESSORIES_COMPLETE
    }

    private record HookPhaseState(HookPhase phase, long at) {}

    private static final Set<String> WATCHED_FILES = Set.of(
        "thunder-plugins-enabled.json",
        "thunder-plugin-settings.json",
        "thunder-chatbubbles-theme.json"
    );
    private static final int WATCH_EVENTS = FileObserver.CLOSE_WRITE
        | FileObserver.CREATE
        | FileObserver.DELETE
        | FileObserver.MODIFY
        | FileObserver.MOVED_FROM
        | FileObserver.MOVED_TO;
    private static final long DEBOUNCE_MS = 120;
    private static final long MAX_DEBOUNCE_MS = 500;
    private static final long FALLBACK_POLL_MS = 2_000;
    private static final long HOOK_PHASE_WINDOW_MS = 1_000;
    private static final long FAILURE_LOG_INTERVAL_MS = 10_000;
    private static final String TAG = "Thunder";
}
