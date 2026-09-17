package ai.hypermemetic.voicevault;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Live system-state checks plus deep-link builders for the in-app Setup &
 * Diagnostics Guide.
 *
 * <p>Every APK update or sideload can silently reset Accessibility, overlay,
 * battery-optimization and permission state on modern Android (13-15). These
 * helpers let {@link SetupActivity} show pass/fail pills and jump the user
 * straight to the system screen that fixes each failure.
 */
public final class SetupDiagnostics {
    private static final String TAG = "SetupDiagnostics";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_TILE_ADDED = "pref_tile_added";

    private SetupDiagnostics() {}

    // ------------------------------------------------------------------
    // Live checks
    // ------------------------------------------------------------------

    /** Microphone permission granted (beamforming audio capture needs it). */
    public static boolean isMicGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Accessibility service enabled. Parses
     * {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} for a component
     * whose class is {@link VoiceVaultKeyService}. When disabled, the volume
     * buttons stop intercepting and pasting ceases to function.
     */
    public static boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String pkg = context.getPackageName();
        String targetClass = VoiceVaultKeyService.class.getName();
        String shortClass = VoiceVaultKeyService.class.getSimpleName();
        for (String entry : enabled.split(":")) {
            if (TextUtils.isEmpty(entry)) continue;
            // Flattened components look like "pkg/pkg.Class" or "pkg/.Class".
            int slash = entry.indexOf('/');
            if (slash < 0) continue;
            String entryPkg = entry.substring(0, slash);
            String entryCls = entry.substring(slash + 1);
            if (!pkg.equalsIgnoreCase(entryPkg)) continue;
            if (targetClass.equalsIgnoreCase(entryCls)
                    || ("." + shortClass).equalsIgnoreCase(entryCls)
                    || entryCls.endsWith("." + shortClass)) {
                return true;
            }
        }
        return false;
    }

    /** "Display over other apps" granted (floating recording timer pill). */
    public static boolean isOverlayGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(context);
    }

    /**
     * Battery optimization ignored for this package ("Unrestricted"). When
     * optimized, Doze can freeze or kill background recordings mid-session.
     */
    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** App notifications currently allowed by the user / system. */
    public static boolean areNotificationsEnabled(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return true;
        return nm.areNotificationsEnabled();
    }

    /**
     * Notifications blocked. Voice Vault is designed to run silent
     * (IMPORTANCE_MIN foreground channel), so blocked is the recommended
     * state.
     */
    public static boolean isNotificationBlocked(Context context) {
        return !areNotificationsEnabled(context);
    }

    /**
     * Quick Settings tile added to the shade. Tracked via
     * {@link VoiceVaultTileService#onTileAdded()} /
     * {@code onTileRemoved()} because Android offers no direct query.
     */
    public static boolean isTileAdded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_TILE_ADDED, false);
    }

    /** Persisted by {@link VoiceVaultTileService} on add/remove callbacks. */
    public static void setTileAdded(Context context, boolean added) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_TILE_ADDED, added).apply();
    }

    /** Any critical breakage that stops dictation keys from working. */
    public static boolean hasCriticalIssue(Context context) {
        return !isAccessibilityEnabled(context) || !isMicGranted(context);
    }

    // ------------------------------------------------------------------
    // Deep-link intent builders
    // ------------------------------------------------------------------

    public static Intent accessibilitySettingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    public static Intent overlaySettingsIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static Intent batteryOptimizationIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static Intent notificationSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        return intent;
    }

    public static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Starts a system settings intent, falling back to the app-details page
     * when an OEM has removed the specific settings activity. Never throws.
     */
    public static boolean openSafely(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Settings activity missing, falling back to app details", e);
        }
        try {
            Intent fallback = appDetailsIntent(context);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "App details activity missing too", e);
            return false;
        }
    }

    /**
     * One-tap "Add tile" prompt on Android 13+ via
     * {@link StatusBarManager#requestAddTileService}. Returns false on older
     * releases, where the user must add the tile manually from the shade
     * editor. The result callback receives
     * {@link StatusBarManager#TILE_ADD_REQUEST_RESULT_TILE_ADDED} and friends.
     */
    public static boolean requestAddTile(Activity activity, Consumer<Integer> callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        try {
            StatusBarManager sbm = activity.getSystemService(StatusBarManager.class);
            if (sbm == null) return false;
            ComponentName tile = new ComponentName(activity, VoiceVaultTileService.class);
            Executor executor = activity.getMainExecutor();
            sbm.requestAddTileService(
                    tile,
                    activity.getString(R.string.tile_name),
                    Icon.createWithResource(activity, R.drawable.ic_mic),
                    executor,
                    callback != null ? callback : result -> {});
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestAddTileService failed", t);
            return false;
        }
    }
}
