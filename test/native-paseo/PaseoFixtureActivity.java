package ai.hypermemetic.voicevault;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Offline Android views reproducing the pinned Paseo inputWrapper/buttonRow. */
public class PaseoFixtureActivity extends Activity {
    static PaseoFixtureActivity instance;
    LinearLayout column, row;
    EditText editor;
    View send;
    int clicks, writes, clickActions, echoDelay;
    boolean rejectClick;
    String submitted;
    boolean multiline;
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle b) { super.onCreate(b); instance = this; configure("Send message", false, true, false, 0); }
    View button(String label) {
        TextView v = new TextView(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (this == send && action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                    clickActions++;
                    if (rejectClick) return false;
                }
                return super.performAccessibilityAction(action, args);
            }
        };
        v.setText("•"); v.setContentDescription(label); v.setGravity(Gravity.CENTER);
        v.setClickable(true); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return v;
    }
    void configure(String label, boolean lines, boolean enabled, boolean duplicate, int delay) {
        clicks = writes = clickActions = 0; rejectClick = false;
        submitted = null; multiline = lines; echoDelay = delay;
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        // Tempting exact GLOBAL control must never be selected.
        View global = button("Send message"); global.setOnClickListener(v -> { throw new AssertionError("global send clicked"); });
        screen.addView(global);
        screen.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Background keeps RN's inputWrapper from being a layout-only collapsed view.
        column.setBackgroundColor(0xffeeeeee);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(dp(12), 0, dp(12), 0); screen.addView(column, cp);
        installEditor("");
        row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(28));
        rp.topMargin = dp(12); rp.leftMargin = rp.rightMargin = -dp(6); column.addView(row, rp);
        row.addView(button("Context window 50% used"));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(28), 1));
        row.addView(button("Voice"));
        if (duplicate) row.addView(button(label));
        send = button(label); send.setEnabled(enabled); row.addView(send);
        send.setOnClickListener(v -> { clicks++; submitted = editor.getText().toString(); installEditor(""); });
        setContentView(screen);
        editor.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(), 0);
    }
    void installEditor(String text) {
        if (editor != null && editor.getParent() == column) column.removeView(editor);
        editor = new EditText(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    writes++;
                    if (echoDelay > 0) {
                        CharSequence value = args.getCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
                        EditText target = this;
                        new Handler().postDelayed(() -> { if (target.getParent() != null) target.setText(value); }, echoDelay);
                        return true;
                    }
                }
                return super.performAccessibilityAction(action, args);
            }
        };
        editor.setId(View.generateViewId()); editor.setHint(PaseoSelection.COMPOSER);
        editor.setContentDescription(PaseoSelection.COMPOSER);
        editor.setPadding(0, 0, 0, 0); editor.setTextSize(14); editor.setText(text);
        editor.setSingleLine(false);
        column.addView(editor, 0, new LinearLayout.LayoutParams(-1, dp(multiline ? 120 : 24)));
    }
    void keyboard(boolean open) {
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (open) { editor.requestFocus(); ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT); }
        else ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
    }
}
