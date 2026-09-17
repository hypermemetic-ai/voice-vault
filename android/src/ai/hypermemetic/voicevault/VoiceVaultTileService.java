package ai.hypermemetic.voicevault;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Quick Settings tile that toggles "Dictation Mode".
 *
 * When enabled, the hardware volume keys are captured by
 * {@link VoiceVaultKeyService}: Volume Up toggles recording and Volume Down
 * pastes the transcript into the focused field. When disabled, volume keys
 * behave normally.
 */
public class VoiceVaultTileService extends TileService {
    private static final String TAG = "VoiceVaultTile";
    private static final String PREFS_NAME = "voice_vault_prefs";
    private static final String PREF_DICTATION_MODE = "pref_dictation_mode_enabled";

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileState();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean enabled = !isDictationModeEnabled();
        setDictationModeEnabled(enabled);
        Log.i(TAG, "Tile clicked: Dictation Mode " + (enabled ? "ENABLED" : "DISABLED"));
        updateTileState();
    }

    private boolean isDictationModeEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_DICTATION_MODE, false);
    }

    private void setDictationModeEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_DICTATION_MODE, enabled).apply();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean enabled = isDictationModeEnabled();
        if (enabled) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Vol Up: Rec | Vol Dn: Paste");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Vol Up: Rec | Vol Dn: Paste");
            }
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_name));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Tap to Enable");
            }
        }
        tile.updateTile();
    }
}
