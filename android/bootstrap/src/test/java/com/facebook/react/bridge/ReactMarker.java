package com.facebook.react.bridge;

import java.util.concurrent.CopyOnWriteArrayList;

public final class ReactMarker {
    private static final CopyOnWriteArrayList<MarkerListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ReactMarker() {
    }

    public static void addListener(MarkerListener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(MarkerListener listener) {
        LISTENERS.remove(listener);
    }

    public static void emit(ReactMarkerConstants marker, String tag) {
        emit(marker, tag, 0);
    }

    public static void emit(ReactMarkerConstants marker, String tag, int instanceKey) {
        for (MarkerListener listener : LISTENERS) listener.logMarker(marker, tag, instanceKey);
    }

    public interface MarkerListener {
        void logMarker(ReactMarkerConstants marker, String tag, int instanceKey);
    }
}
