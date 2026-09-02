package dev.thunder.bootstrap;

import android.content.Context;
import android.content.SharedPreferences;

final class BootControlStore {
    static final String PREFERENCES = "thunder_boot_control";
    static final String PROFILE = "requested_profile";
    private static final String LAUNCH_IN_PROGRESS = "launch_in_progress";
    private static final String CONSECUTIVE_FAILURES = "consecutive_failures";

    private BootControlStore() {
    }

    static String profile(Context context) {
        return preferences(context).getString(PROFILE, "normal");
    }

    static void setProfile(Context context, String profile) {
        if (!isValidProfile(profile)) throw new IllegalArgumentException("Unknown boot profile");
        if (!preferences(context).edit().putString(PROFILE, profile).commit()) {
            throw new IllegalStateException("Could not persist boot profile");
        }
    }

    static BootDecision beginLaunch(Context context) {
        SharedPreferences preferences = preferences(context);
        int failures = preferences.getInt(CONSECUTIVE_FAILURES, 0);
        if (preferences.getBoolean(LAUNCH_IN_PROGRESS, false)) failures = Math.min(failures + 1, 100);
        if (!preferences.edit()
            .putBoolean(LAUNCH_IN_PROGRESS, true)
            .putInt(CONSECUTIVE_FAILURES, failures)
            .commit()) {
            throw new IllegalStateException("Could not persist launch marker");
        }

        String requested = profile(context);
        String effective = failures >= 5
            ? "mod-disabled"
            : failures >= 3 ? "defaults-only" : requested;
        return new BootDecision(requested, effective, failures);
    }

    static void markReady(Context context) {
        if (!preferences(context).edit()
            .putBoolean(LAUNCH_IN_PROGRESS, false)
            .putInt(CONSECUTIVE_FAILURES, 0)
            .commit()) {
            throw new IllegalStateException("Could not persist ready marker");
        }
    }

    static void resetCrashLoop(Context context) {
        if (!preferences(context).edit()
            .putBoolean(LAUNCH_IN_PROGRESS, false)
            .putInt(CONSECUTIVE_FAILURES, 0)
            .commit()) {
            throw new IllegalStateException("Could not reset crash loop");
        }
    }

    static int consecutiveFailures(Context context) {
        return preferences(context).getInt(CONSECUTIVE_FAILURES, 0);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean isValidProfile(String profile) {
        return "normal".equals(profile)
            || "defaults-only".equals(profile)
            || "no-external-plugins".equals(profile)
            || "no-themes".equals(profile)
            || "compatibility-quarantine".equals(profile)
            || "mod-disabled".equals(profile);
    }

    record BootDecision(String requestedProfile, String effectiveProfile, int consecutiveFailures) {
    }
}
