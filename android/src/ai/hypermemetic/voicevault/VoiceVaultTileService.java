package ai.hypermemetic.voicevault;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public class VoiceVaultTileService extends TileService {
    private static final String TAG = "VoiceVaultTile";
    private BroadcastReceiver mReceiver;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();

        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateTileState();
                }
            };
            IntentFilter filter = new IntentFilter(VoiceVaultService.BROADCAST_STATE_CHANGE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mReceiver, filter);
            }
        }
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        if (mReceiver != null) {
            try {
                unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mReceiver = null;
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean recording = VoiceVaultService.isRecording();
        Intent intent = new Intent(this, VoiceVaultService.class);
        if (recording) {
            Log.i(TAG, "Tile clicked: Stopping dictation");
            intent.setAction(VoiceVaultService.ACTION_STOP);
        } else {
            Log.i(TAG, "Tile clicked: Starting dictation");
            intent.setAction(VoiceVaultService.ACTION_START);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean active = VoiceVaultService.isRecording();
        if (active) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.tile_recording));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Tap to Finish");
            }
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_name));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Tap to Record");
            }
        }
        tile.updateTile();
    }
}
