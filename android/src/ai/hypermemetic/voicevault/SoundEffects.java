package ai.hypermemetic.voicevault;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class SoundEffects {
    private static final String TAG = "SoundEffects";
    private static final int SAMPLE_RATE = 44100;

    private static byte[] sStartPopData = null;
    private static byte[] sSuccessChimeData = null;
    private static AudioTrack sStartTrack = null;
    private static AudioTrack sSuccessTrack = null;

    static {
        try {
            sStartPopData = generateStartPop();
            sSuccessChimeData = generateSuccessChime();
        } catch (Exception e) {
            Log.e(TAG, "Failed to precompute sound effects", e);
        }
    }

    private static byte[] generateStartPop() {
        int durationMs = 35;
        int totalSamples = (int) (SAMPLE_RATE * (durationMs / 1000.0));
        byte[] buffer = new byte[totalSamples * 2];

        double startFreq = 520.0;
        double endFreq = 820.0;
        double durationSec = durationMs / 1000.0;

        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            // Phase for linear chirp: phi = 2*pi * (f0*t + ((f1-f0)/(2*T)) * t^2)
            double phase = 2.0 * Math.PI * (startFreq * t + ((endFreq - startFreq) / (2.0 * durationSec)) * t * t);
            // Hann window envelope for zero start/end transient clicks
            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (totalSamples - 1)));
            double sampleValue = Math.sin(phase) * window * 0.85;

            short val = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (long) (sampleValue * 32767.0)));
            buffer[i * 2] = (byte) (val & 0xFF);
            buffer[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return buffer;
    }

    private static byte[] generateSuccessChime() {
        int durationMs = 65;
        int totalSamples = (int) (SAMPLE_RATE * (durationMs / 1000.0));
        byte[] buffer = new byte[totalSamples * 2];

        double f1 = 880.0;  // A5
        double f2 = 1320.0; // E6 (musical perfect fifth)
        int attackSamples = (int) (SAMPLE_RATE * 0.005); // 5ms attack

        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double attack = (i < attackSamples) ? ((double) i / attackSamples) : 1.0;
            double decay = Math.exp(-t / 0.024);
            double envelope = attack * decay;

            // Fade out smoothly at very end to prevent DAC click
            if (i > totalSamples - 50) {
                envelope *= (double) (totalSamples - i) / 50.0;
            }

            double sampleValue = (Math.sin(2.0 * Math.PI * f1 * t) * 0.58 +
                                  Math.sin(2.0 * Math.PI * f2 * t) * 0.32) * envelope;

            short val = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (long) (sampleValue * 32767.0)));
            buffer[i * 2] = (byte) (val & 0xFF);
            buffer[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return buffer;
    }

    private static AudioTrack createStaticTrack(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(data.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            track.write(data, 0, data.length);
            return track;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create AudioTrack", e);
            return null;
        }
    }

    public static synchronized void playStartPop() {
        try {
            if (sStartTrack == null || sStartTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                sStartTrack = createStaticTrack(sStartPopData);
            }
            if (sStartTrack != null) {
                sStartTrack.pause();
                sStartTrack.setPlaybackHeadPosition(0);
                sStartTrack.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing start pop", e);
        }
    }

    public static synchronized void playSuccessChime() {
        try {
            if (sSuccessTrack == null || sSuccessTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                sSuccessTrack = createStaticTrack(sSuccessChimeData);
            }
            if (sSuccessTrack != null) {
                sSuccessTrack.pause();
                sSuccessTrack.setPlaybackHeadPosition(0);
                sSuccessTrack.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing success chime", e);
        }
    }
}
