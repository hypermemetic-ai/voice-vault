package ai.hypermemetic.voicevault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** One recording's upload; cancellation never blocks the UI thread. */
final class DictationUpload {
    private volatile boolean canceled;
    private volatile HttpURLConnection connection;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    static final class Failure extends IOException {
        final String feedback;
        Failure(String feedback) {
            super(feedback);
            this.feedback = feedback;
        }
    }

    void cancel() { canceled = true; }

    /** Call on a worker: some implementations lock disconnect during blocking I/O. */
    void disconnect() {
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    private void checkCanceled() throws IOException {
        if (canceled) throw new Failure("Transcription canceled");
    }

    String post(String endpoint, File audio, long durationMs) throws IOException {
        return post(endpoint, audio, durationMs, 30000, 600000);
    }

    String post(String endpoint, File audio, long durationMs, int connectMs, int readMs) throws IOException {
        checkCanceled();
        if (audio == null || !audio.isFile() || audio.length() == 0) {
            throw new Failure("No recording audio — try again");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        connection = conn;
        try {
            checkCanceled();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Content-Type", "audio/mp4");
            conn.setRequestProperty("X-Duration-Ms", String.valueOf(durationMs));
            conn.setRequestProperty("X-Device", "Pixel 10 Native");
            conn.setFixedLengthStreamingMode(audio.length());
            try (OutputStream out = conn.getOutputStream(); FileInputStream in = new FileInputStream(audio)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCanceled();
                    out.write(buffer, 0, read);
                }
            }
            checkCanceled();
            int status = conn.getResponseCode();
            if (status != 200) throw new Failure("Server error (HTTP " + status + ")");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCanceled();
                    if (body.size() + read > MAX_RESPONSE_BYTES) throw new Failure("Invalid server response");
                    body.write(buffer, 0, read);
                }
                checkCanceled();
                return body.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
            connection = null;
        }
    }

    static String feedback(Exception error) {
        if (error instanceof Failure) return ((Failure) error).feedback;
        if (error instanceof SocketTimeoutException) return "Transcription timed out — try again";
        if (error instanceof UnknownHostException || error instanceof ConnectException) return "Cannot reach server — check Tailscale";
        if (error instanceof SSLException) return "Secure connection failed";
        if (error instanceof IOException) return "Upload failed — check connection";
        return "Invalid server response";
    }
}
