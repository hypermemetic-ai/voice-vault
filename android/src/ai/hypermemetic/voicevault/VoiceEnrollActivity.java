package ai.hypermemetic.voicevault;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native "Enroll Voice" flow.
 *
 * Records up to three ~5 s clips with the same VOICE_RECOGNITION beamforming
 * source the dictation service uses, uploads them to /api/profile/enroll and
 * shows the resulting calibration (mu, sigma, threshold = mu - 3 sigma) plus
 * the live enrollment state. Also offers a "test my voice" verification and a
 * profile reset.
 */
public class VoiceEnrollActivity extends Activity {

    private static final int PERMISSION_REQ_CODE = 200;
    private static final int RECOMMENDED_CLIPS = 3;
    private static final int MAX_CLIPS = 8;
    private static final long MAX_CLIP_MS = 8000L;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<File> mClips = new ArrayList<>();

    private TextView mSlot1;
    private TextView mSlot2;
    private TextView mSlot3;
    private TextView mTimer;
    private TextView mStatus;
    private TextView mResult;
    private Button mBtnRecord;
    private Button mBtnSubmit;
    private Button mBtnTest;
    private Button mBtnRemove;

    private MediaRecorder mRecorder;
    private boolean mRecording = false;
    private boolean mBusy = false;
    private boolean mPendingTest = false;
    private long mClipStartedAt = 0;
    private File mCurrentClip;
    private boolean mEnrolled = false;

    private final Runnable mTimerTick = new Runnable() {
        @Override
        public void run() {
            if (!mRecording) return;
            long elapsed = System.currentTimeMillis() - mClipStartedAt;
            mTimer.setText(String.format(Locale.US, "%02d:%02d", elapsed / 1000 / 60, (elapsed / 1000) % 60));
            if (elapsed >= MAX_CLIP_MS) {
                stopClip();
                return;
            }
            mHandler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_enroll);

        mSlot1 = findViewById(R.id.tv_slot_1);
        mSlot2 = findViewById(R.id.tv_slot_2);
        mSlot3 = findViewById(R.id.tv_slot_3);
        mTimer = findViewById(R.id.tv_enroll_timer);
        mStatus = findViewById(R.id.tv_enroll_status);
        mResult = findViewById(R.id.tv_enroll_result);
        mBtnRecord = findViewById(R.id.btn_record_clip);
        mBtnSubmit = findViewById(R.id.btn_submit_enroll);
        mBtnTest = findViewById(R.id.btn_test_voice);
        mBtnRemove = findViewById(R.id.btn_remove_profile);

        findViewById(R.id.btn_close_enroll).setOnClickListener(v -> finish());
        mBtnRecord.setOnClickListener(v -> {
            if (mRecording) stopClip();
            else startClip(false);
        });
        mBtnSubmit.setOnClickListener(v -> submitEnrollment());
        mBtnTest.setOnClickListener(v -> startClip(true));
        mBtnRemove.setOnClickListener(v -> removeProfile());

        refreshStatus();
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    private void refreshStatus() {
        mExecutor.execute(() -> {
            try {
                JSONObject status = VoiceVaultApi.request(VoiceVaultApi.PROFILE_STATUS_URL, "GET");
                mHandler.post(() -> renderStatus(status));
            } catch (Exception e) {
                mHandler.post(() -> {
                    mEnrolled = false;
                    mStatus.setText("Voice profile unavailable: " + e.getMessage());
                    renderButtons();
                });
            }
        });
    }

    private void renderStatus(JSONObject status) {
        mEnrolled = status.optBoolean("enrolled", false);
        if (mEnrolled) {
            mStatus.setText(String.format(Locale.US,
                    "Enrolled · %d clip(s) · %s (%d-d)\nThreshold %.3f = μ %.3f − 3σ %.3f\n%s",
                    status.optInt("sampleCount", 0),
                    status.optString("modelId", "model"),
                    status.optInt("dim", 0),
                    status.optDouble("threshold", 0),
                    status.optDouble("mu", 0),
                    status.optDouble("sigma", 0),
                    status.optString("backend", "")));
        } else {
            mStatus.setText("Not enrolled — the speaker gate is off and every voice is transcribed. "
                    + "Record " + RECOMMENDED_CLIPS + " clips of ~5s to activate it.");
        }
        renderButtons();
        renderSlots();
    }

    private void renderButtons() {
        mBtnRecord.setEnabled(!mBusy && mClips.size() < MAX_CLIPS);
        mBtnSubmit.setEnabled(!mBusy && !mClips.isEmpty());
        mBtnTest.setEnabled(!mBusy && mEnrolled);
        mBtnRemove.setVisibility(mEnrolled ? View.VISIBLE : View.GONE);
        mBtnRecord.setText(mRecording ? "■ STOP CLIP" : "● RECORD CLIP");
    }

    private void renderSlots() {
        TextView[] slots = {mSlot1, mSlot2, mSlot3};
        for (int i = 0; i < slots.length; i++) {
            if (i < mClips.size()) {
                slots[i].setText(String.format(Locale.US, "● Clip %d · captured (%.1fs)", i + 1,
                        mClips.get(i).length() / 16000.0));
                slots[i].setTextColor(0xFF22C55E);
            } else if (mRecording && i == mClips.size()) {
                slots[i].setText("● Clip " + (i + 1) + " · recording…");
                slots[i].setTextColor(0xFFEF4444);
            } else {
                slots[i].setText("○ Clip " + (i + 1) + " · not recorded");
                slots[i].setTextColor(0xFF6F6F6F);
            }
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private void startClip(boolean forTest) {
        if (mBusy || mRecording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mPendingTest = forTest;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
            return;
        }

        try {
            File dir = new File(getCacheDir(), "enroll");
            dir.mkdirs();
            mCurrentClip = new File(dir, (forTest ? "verify_" : "enroll_") + System.currentTimeMillis() + ".m4a");

            mRecorder = new MediaRecorder();
            // Same beamforming capture path as dictation, so the enrolled
            // voiceprint matches what the transcribe endpoint will see.
            mRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(96000);
            mRecorder.setAudioSamplingRate(16000);
            mRecorder.setOutputFile(mCurrentClip.getAbsolutePath());
            mRecorder.prepare();
            mRecorder.start();

            mRecording = true;
            mPendingTest = forTest;
            mClipStartedAt = System.currentTimeMillis();
            mResult.setVisibility(View.GONE);
            renderSlots();
            renderButtons();
            mHandler.post(mTimerTick);
        } catch (Exception e) {
            showResult("Recording failed: " + e.getMessage(), false);
            releaseRecorder();
        }
    }

    private void stopClip() {
        if (!mRecording) return;
        mRecording = false;
        mHandler.removeCallbacks(mTimerTick);
        try {
            mRecorder.stop();
        } catch (Exception ignored) {
        }
        releaseRecorder();
        mTimer.setText("00:00");

        if (mPendingTest) {
            mPendingTest = false;
            if (mCurrentClip != null && mCurrentClip.exists() && mCurrentClip.length() > 44) {
                verifyClip(mCurrentClip);
            }
        } else if (mCurrentClip != null && mCurrentClip.exists() && mCurrentClip.length() > 44) {
            mClips.add(mCurrentClip);
            mCurrentClip = null;
        }
        renderSlots();
        renderButtons();
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.reset();
            } catch (Exception ignored) {
            }
            try {
                mRecorder.release();
            } catch (Exception ignored) {
            }
            mRecorder = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startClip(mPendingTest);
            } else {
                showResult("Microphone permission is required to enroll your voice.", false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Server calls
    // ------------------------------------------------------------------

    private void submitEnrollment() {
        if (mBusy || mClips.isEmpty()) return;
        mBusy = true;
        renderButtons();
        showResult("Extracting voiceprint from " + mClips.size() + " clip(s)…", true);

        List<File> clips = new ArrayList<>(mClips);
        mExecutor.execute(() -> {
            try {
                JSONObject response = VoiceVaultApi.postAudioFiles(
                        VoiceVaultApi.PROFILE_ENROLL_URL, clips, "sample");
                mHandler.post(() -> {
                    mBusy = false;
                    mClips.clear();
                    showResult(String.format(Locale.US,
                            "✓ Voice profile enrolled\nModel %s (%d-d)\nThreshold %.3f = μ %.3f − 3σ %.3f\nOther speakers are now rejected before transcription.",
                            response.optString("modelId", "model"),
                            response.optInt("dim", 0),
                            response.optDouble("threshold", 0),
                            response.optDouble("mu", 0),
                            response.optDouble("sigma", 0)), true);
                    refreshStatus();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mBusy = false;
                    renderButtons();
                    showResult("Enrollment failed: " + e.getMessage(), false);
                });
            }
        });
    }

    private void verifyClip(File clip) {
        mBusy = true;
        renderButtons();
        showResult("Scoring your voice against the enrolled gallery…", true);
        List<File> clips = new ArrayList<>();
        clips.add(clip);
        mExecutor.execute(() -> {
            try {
                JSONObject response = VoiceVaultApi.postAudioFiles(
                        VoiceVaultApi.PROFILE_VERIFY_URL, clips, "sample");
                boolean accepted = response.optBoolean("accepted", false);
                mHandler.post(() -> {
                    mBusy = false;
                    showResult(String.format(Locale.US,
                            "%s\nScore %.3f vs threshold %.3f (z %.2f, %.1fs)%s",
                            accepted ? "✓ That sounds like you" : "✕ Different voice detected",
                            response.optDouble("score", 0),
                            response.optDouble("threshold", 0),
                            response.optDouble("z", 0),
                            response.optDouble("durationMs", 0) / 1000.0,
                            response.optBoolean("shortUtteranceRelaxed", false)
                                    ? "\nLength-adaptive relaxation applied for this short clip."
                                    : ""), accepted);
                    renderButtons();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mBusy = false;
                    renderButtons();
                    showResult("Verification failed: " + e.getMessage(), false);
                });
            } finally {
                if (clip.exists()) clip.delete();
            }
        });
    }

    private void removeProfile() {
        if (mBusy) return;
        mBusy = true;
        renderButtons();
        mExecutor.execute(() -> {
            try {
                VoiceVaultApi.request(VoiceVaultApi.PROFILE_URL, "DELETE");
                mHandler.post(() -> {
                    mBusy = false;
                    mClips.clear();
                    showResult("Voice profile removed — all speakers are transcribed again.", true);
                    refreshStatus();
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mBusy = false;
                    renderButtons();
                    showResult("Could not remove profile: " + e.getMessage(), false);
                });
            }
        });
    }

    private void showResult(String message, boolean good) {
        mResult.setVisibility(View.VISIBLE);
        mResult.setText(message);
        mResult.setTextColor(good ? 0xFF22C55E : 0xFFEF4444);
    }

    @Override
    protected void onDestroy() {
        mRecording = false;
        mHandler.removeCallbacks(mTimerTick);
        releaseRecorder();
        for (File clip : mClips) {
            if (clip.exists()) clip.delete();
        }
        if (mCurrentClip != null && mCurrentClip.exists()) mCurrentClip.delete();
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
