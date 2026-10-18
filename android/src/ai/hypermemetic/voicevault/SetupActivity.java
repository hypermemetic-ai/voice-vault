package ai.hypermemetic.voicevault;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-app Setup &amp; Diagnostics Guide.
 *
 * <p>Every APK update or sideload can silently reset Android system settings
 * (Accessibility, overlay, battery optimization, permissions). Each section shows
 * a live pass/fail status plus a 1-tap deep link to the system screen that fixes
 * it. {@link #onResume()} refreshes every pill so returning from system
 * settings immediately shows the new state.
 */
public class SetupActivity extends Activity {
    private static final int MIC_REQ_CODE = 300;

    private LinearLayout mCards;
    private final Map<String, TextView> mPills = new LinkedHashMap<>();
    private final Map<String, Button> mButtons = new LinkedHashMap<>();
    private Typeface mDemi;
    private Typeface mBook;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        try {
            mDemi = getResources().getFont(R.font.urw_gothic_demi);
        } catch (Exception ignored) {}
        try {
            mBook = getResources().getFont(R.font.urw_gothic_book);
        } catch (Exception ignored) {}

        findViewById(R.id.btn_close_setup).setOnClickListener(v -> finish());

        mCards = findViewById(R.id.layout_setup_cards);
        buildCards();
        refreshStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from a system settings screen must immediately show the
        // new state without requiring an app restart.
        refreshStatuses();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_REQ_CODE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this,
                    granted ? "Microphone granted" : "Microphone denied — recording cannot start",
                    Toast.LENGTH_SHORT).show();
            refreshStatuses();
        }
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private void buildCards() {
        addCard("accessibility",
                "Dictation keys",
                "The Accessibility service powers the volume keys. Tap Volume Up to start/stop recording. Tap Volume Down to finish, add the text after your draft in the composer and send it when Auto-send is on. Hold Volume Down to toggle Auto-send; hold Volume Up to turn Dictation Mode on or off. "
                        + "Android updates often switch this service off — when it is off, "
                        + "the volume buttons do nothing.",
                "Android 13/14 sideload fix: if the toggle is greyed out as "
                        + "\"Restricted settings\", open Settings → Apps → Voice Vault → "
                        + "⋮ menu (top right) → \"Allow restricted settings\", then "
                        + "enable the service here.",
                "OPEN ACCESSIBILITY SETTINGS",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.accessibilitySettingsIntent()));

        addCard("mic",
                "Microphone access",
                "Beamforming audio capture (VOICE_RECOGNITION) needs the "
                        + "microphone. Without it, recording cannot start.",
                null,
                "GRANT PERMISSION",
                v -> {
                    if (SetupDiagnostics.isMicGranted(this)) {
                        Toast.makeText(this, "Microphone already granted",
                                Toast.LENGTH_SHORT).show();
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(
                                new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ_CODE);
                    }
                });

        addCard("overlay",
                "Display over other apps",
                "Lets the floating recording timer pill show on top of other apps "
                        + "while you dictate.",
                null,
                "OPEN OVERLAY SETTINGS",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.overlaySettingsIntent(this)));

        addCard("battery",
                "Unrestricted battery",
                "Prevents Android Doze from freezing or killing background "
                        + "recordings during longer sessions.",
                "On the next screen choose \"Unrestricted\" (not \"Optimized\").",
                "DISABLE BATTERY OPTIMIZATION",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.batteryOptimizationIntent(this)));

        addCard("tile",
                "Quick Settings tile",
                "The drop-down shade tile toggles Dictation Mode (volume keys "
                        + "capture). Long-press it to open the dashboard.",
                "Manual add: swipe down twice → tap ✎ Edit → drag "
                        + "\"Voice Dictate\" into the shade.",
                "ADD TILE TO QUICK SETTINGS",
                v -> {
                    boolean prompted = SetupDiagnostics.requestAddTile(this, result -> {
                        runOnUiThread(() -> {
                            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                Toast.makeText(this, "Tile added to Quick Settings",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this,
                                        "Tile not added — use the manual steps above",
                                        Toast.LENGTH_LONG).show();
                            }
                            refreshStatuses();
                        });
                    });
                    if (!prompted) {
                        Toast.makeText(this,
                                "Swipe down twice → ✎ Edit → drag \"Voice Dictate\" up",
                                Toast.LENGTH_LONG).show();
                    }
                });

        addCard("notifications",
                "Notifications",
                "Voice Vault runs silent by design (IMPORTANCE_MIN foreground "
                        + "channel). Keeping notifications blocked is recommended.",
                null,
                "NOTIFICATION SETTINGS",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.notificationSettingsIntent(this)));

    }

    private void addCard(String key, String title, String description,
                         String note, String buttonLabel, View.OnClickListener action) {
        // One section per check: no surface, sections separated by a hairline.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(20), 0, dp(20));
        if (mCards.getChildCount() > 0) {
            View rule = new View(this);
            rule.setBackgroundColor(getColor(R.color.divider));
            mCards.addView(rule, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        }

        // Header: title and live status.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getColor(R.color.text));
        tvTitle.setTextSize(15f);
        if (mDemi != null) tvTitle.setTypeface(mDemi);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.rightMargin = dp(12);
        header.addView(tvTitle, titleLp);

        TextView pill = new TextView(this);
        pill.setTextSize(12f);
        if (mBook != null) pill.setTypeface(mBook);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_dot, 0, 0, 0);
        pill.setCompoundDrawablePadding(dp(8));
        header.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mPills.put(key, pill);

        card.addView(header);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(description);
        tvDesc.setTextColor(getColor(R.color.text_muted));
        tvDesc.setTextSize(13f);
        if (mBook != null) tvDesc.setTypeface(mBook);
        tvDesc.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(8);
        card.addView(tvDesc, descLp);

        if (note != null) {
            TextView tvNote = new TextView(this);
            tvNote.setText(note);
            tvNote.setTextColor(getColor(R.color.text_faint));
            tvNote.setTextSize(12f);
            if (mBook != null) tvNote.setTypeface(mBook);
            tvNote.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = dp(8);
            card.addView(tvNote, noteLp);
        }

        Button button = new Button(this);
        button.setText(buttonLabel);
        button.setTextSize(12f);
        button.setLetterSpacing(0.06f);
        button.setAllCaps(true);
        button.setStateListAnimator(null);
        button.setBackgroundResource(R.drawable.bg_btn_tonal);
        button.setPadding(dp(16), 0, dp(16), 0);
        if (mDemi != null) button.setTypeface(mDemi);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        btnLp.topMargin = dp(12);
        card.addView(button, btnLp);
        button.setOnClickListener(action);
        mButtons.put(key, button);
        styleButton(button, false);

        mCards.addView(card);
    }

    /** A failing check gets full-strength button text; a passing one stays quiet. */
    private void styleButton(Button button, boolean needsFix) {
        button.setTextColor(getColor(needsFix ? R.color.text : R.color.text_muted));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Live status
    // ------------------------------------------------------------------

    private void refreshStatuses() {
        setPill("accessibility",
                SetupDiagnostics.isAccessibilityEnabled(this), "Enabled", "Disabled");
        setPill("mic",
                SetupDiagnostics.isMicGranted(this), "Granted", "Denied");
        setPill("overlay",
                SetupDiagnostics.isOverlayGranted(this), "Enabled", "Disabled");
        setPill("battery",
                SetupDiagnostics.isBatteryOptimizationIgnored(this), "Unrestricted", "Optimized");
        setPill("tile",
                SetupDiagnostics.isTileAdded(this), "Added", "Not added");
        TextView notif = mPills.get("notifications");
        if (notif != null) {
            if (SetupDiagnostics.isNotificationBlocked(this)) {
                stylePill(notif, true, "Blocked (Recommended)");
            } else {
                // Allowed works; it is just not the recommended silent setup.
                applyPill(notif, "Allowed", getColor(R.color.text_muted), getColor(R.color.text_faint));
            }
        }
    }

    private void setPill(String key, boolean ok, String okLabel, String badLabel) {
        TextView pill = mPills.get(key);
        if (pill == null) return;
        stylePill(pill, ok, ok ? okLabel : badLabel);
        Button button = mButtons.get(key);
        if (button != null) styleButton(button, !ok);
    }

    private void stylePill(TextView pill, boolean ok, String text) {
        int c = getColor(ok ? R.color.success : R.color.record);
        applyPill(pill, text, ok ? getColor(R.color.text_muted) : c, c);
    }

    private void applyPill(TextView pill, String text, int textColor, int dotColor) {
        pill.setText(text);
        pill.setTextColor(textColor);
        pill.setCompoundDrawableTintList(ColorStateList.valueOf(dotColor));
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
