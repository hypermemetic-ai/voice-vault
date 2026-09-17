package ai.hypermemetic.voicevault;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * (Accessibility, overlay, battery optimization, permissions). Each card shows
 * a live pass/fail pill plus a 1-tap deep link to the system screen that fixes
 * it. {@link #onResume()} refreshes every pill so returning from system
 * settings immediately shows the new state.
 */
public class SetupActivity extends Activity {
    private static final int MIC_REQ_CODE = 300;

    private LinearLayout mCards;
    private final Map<String, TextView> mPills = new LinkedHashMap<>();
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
                "1 · DICTATION KEYS (ACCESSIBILITY SERVICE)",
                "Volume Up toggles recording, Volume Down pastes the transcript. "
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
                "2 · MICROPHONE ACCESS",
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
                "3 · DISPLAY OVER OTHER APPS (OVERLAY PILL)",
                "Shows the floating recording timer pill on top of other apps "
                        + "while you dictate.",
                null,
                "OPEN OVERLAY SETTINGS",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.overlaySettingsIntent(this)));

        addCard("battery",
                "4 · UNRESTRICTED BATTERY",
                "Prevents Android Doze from freezing or killing background "
                        + "recordings during longer sessions.",
                "On the next screen choose \"Unrestricted\" (not \"Optimized\").",
                "DISABLE BATTERY OPTIMIZATION",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.batteryOptimizationIntent(this)));

        addCard("tile",
                "5 · QUICK SETTINGS TILE",
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
                "6 · NOTIFICATIONS",
                "Voice Vault runs silent by design (IMPORTANCE_MIN foreground "
                        + "channel). Keeping notifications blocked is recommended.",
                null,
                "NOTIFICATION SETTINGS",
                v -> SetupDiagnostics.openSafely(this,
                        SetupDiagnostics.notificationSettingsIntent(this)));

        addFooterCard();
    }

    private void addFooterCard() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout card = newCardContainer(density);

        TextView title = new TextView(this);
        title.setText("TROUBLESHOOTING AFTER UPDATES");
        title.setTextColor(Color.parseColor("#FFFFFF"));
        title.setTextSize(13f);
        if (mDemi != null) title.setTypeface(mDemi);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("• Dictation keys dead? Re-enable the Accessibility service in card 1.\n"
                + "• Grey \"Restricted settings\" toggle? App Info → ⋮ → Allow restricted settings.\n"
                + "• Recording dies mid-session? Set battery to Unrestricted (card 4).\n"
                + "• No floating pill? Re-grant \"Display over other apps\" (card 3).\n"
                + "• Statuses refresh automatically when you return from system settings.");
        body.setTextColor(Color.parseColor("#999999"));
        body.setTextSize(12f);
        if (mBook != null) body.setTypeface(mBook);
        body.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * density);
        card.addView(body, lp);

        mCards.addView(card);
    }

    private void addCard(String key, String title, String description,
                         String note, String buttonLabel, View.OnClickListener action) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout card = newCardContainer(density);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#FFFFFF"));
        tvTitle.setTextSize(13f);
        if (mDemi != null) tvTitle.setTypeface(mDemi);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.rightMargin = Math.round(8 * density);
        header.addView(tvTitle, titleLp);

        TextView pill = new TextView(this);
        pill.setTextSize(11f);
        if (mDemi != null) pill.setTypeface(mDemi);
        pill.setPadding(Math.round(10 * density), Math.round(4 * density),
                Math.round(10 * density), Math.round(4 * density));
        header.addView(pill);
        mPills.put(key, pill);

        card.addView(header);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(description);
        tvDesc.setTextColor(Color.parseColor("#999999"));
        tvDesc.setTextSize(12f);
        if (mBook != null) tvDesc.setTypeface(mBook);
        tvDesc.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = Math.round(8 * density);
        card.addView(tvDesc, descLp);

        if (note != null) {
            TextView tvNote = new TextView(this);
            tvNote.setText(note);
            tvNote.setTextColor(Color.parseColor("#777777"));
            tvNote.setTextSize(11f);
            if (mBook != null) tvNote.setTypeface(mBook);
            tvNote.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = Math.round(6 * density);
            card.addView(tvNote, noteLp);
        }

        Button button = new Button(this);
        button.setText(buttonLabel);
        button.setBackgroundColor(Color.parseColor("#1C1C1C"));
        button.setTextColor(Color.parseColor("#FFFFFF"));
        button.setTextSize(11f);
        if (mDemi != null) button.setTypeface(mDemi);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * density));
        btnLp.topMargin = Math.round(10 * density);
        card.addView(button, btnLp);
        button.setOnClickListener(action);

        mCards.addView(card);
    }

    private LinearLayout newCardContainer(float density) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_history_card);
        int pad = Math.round(16 * density);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = Math.round(12 * density);
        card.setLayoutParams(cardLp);
        return card;
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
                stylePill(notif, true, "\u2705 Blocked (Recommended)");
            } else {
                stylePillWarn(notif, "\u26A0 Allowed");
            }
        }
    }

    private void setPill(String key, boolean ok, String okLabel, String badLabel) {
        TextView pill = mPills.get(key);
        if (pill == null) return;
        stylePill(pill, ok, ok ? "\u2705 " + okLabel : "\u274C " + badLabel);
    }

    private void stylePill(TextView pill, boolean ok, String text) {
        pill.setText(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f);
        if (ok) {
            bg.setColor(Color.parseColor("#0F2A1A"));
            bg.setStroke(2, Color.parseColor("#22C55E"));
            pill.setTextColor(Color.parseColor("#22C55E"));
        } else {
            bg.setColor(Color.parseColor("#2A0F0F"));
            bg.setStroke(2, Color.parseColor("#EF4444"));
            pill.setTextColor(Color.parseColor("#EF4444"));
        }
        pill.setBackground(bg);
    }

    private void stylePillWarn(TextView pill, String text) {
        pill.setText(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f);
        bg.setColor(Color.parseColor("#2A230F"));
        bg.setStroke(2, Color.parseColor("#EAB308"));
        pill.setTextColor(Color.parseColor("#EAB308"));
        pill.setBackground(bg);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
