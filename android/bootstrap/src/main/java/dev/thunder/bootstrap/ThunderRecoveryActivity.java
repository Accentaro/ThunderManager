package dev.thunder.bootstrap;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ThunderRecoveryActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Thunder recovery");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        TextView heading = new TextView(this);
        heading.setText("Thunder native recovery");
        heading.setTextSize(24);
        content.addView(heading, matchWidth());

        status = new TextView(this);
        content.addView(status, matchWidth());
        content.addView(profileButton("Start normally", "normal"));
        content.addView(profileButton("Use built-in defaults", "defaults-only"));
        content.addView(profileButton("Disable external plugins", "no-external-plugins"));
        content.addView(profileButton("Disable themes", "no-themes"));
        content.addView(profileButton("Quarantine compatibility pack", "compatibility-quarantine"));
        content.addView(profileButton("Disable Thunder runtime", "mod-disabled"));

        setContentView(content);
        refreshStatus();
    }

    private Button profileButton(String label, String profile) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> {
            BootControlStore.setProfile(this, profile);
            BootControlStore.resetCrashLoop(this);
            refreshStatus();
        });
        button.setLayoutParams(matchWidth());
        return button;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void refreshStatus() {
        status.setText(
            "Current profile: " + BootControlStore.profile(this)
                + "\nConsecutive failed launches: " + BootControlStore.consecutiveFailures(this)
                + "\nBootstrap: " + BuildConfig.BOOTSTRAP_VERSION
                + "\nDelegated factory: " + valueOrUnknown(BootstrapStatus.delegatedFactory())
                + "\nRuntime entrypoint: " + valueOrUnknown(BootstrapStatus.runtimeEntrypoint())
                + "\nRuntime asset: " + valueOrUnknown(BootstrapStatus.runtimeAsset())
                + "\nRuntime profile: " + valueOrUnknown(BootstrapStatus.runtimeProfile())
                + "\nRuntime state: " + valueOrUnknown(BootstrapStatus.runtimeState())
                + "\nLast bootstrap failure: " + valueOrUnknown(BootstrapStatus.failureCode())
        );
    }

    private static String valueOrUnknown(String value) {
        return value == null ? "none" : value;
    }
}
