package dev.thunder.bootstrap;

import android.content.Context;

public final class RuntimeBootCoordinator {
    private RuntimeBootCoordinator() {
    }

    public static BootSession begin(Context context) {
        BootControlStore.BootDecision decision = BootControlStore.beginLaunch(context.getApplicationContext());
        return new BootSession(context.getApplicationContext(), decision);
    }

    public static final class BootSession {
        private final Context context;
        private final BootControlStore.BootDecision decision;
        private boolean completed;

        private BootSession(Context context, BootControlStore.BootDecision decision) {
            this.context = context;
            this.decision = decision;
        }

        public String requestedProfile() {
            return decision.requestedProfile();
        }

        public String effectiveProfile() {
            return decision.effectiveProfile();
        }

        public int consecutiveFailures() {
            return decision.consecutiveFailures();
        }

        public synchronized void markReady() {
            if (completed) return;
            BootControlStore.markReady(context);
            completed = true;
        }
    }
}
