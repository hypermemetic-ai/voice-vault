package ai.hypermemetic.voicevault;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Shared HTTP endpoints for the Voice Vault backend, including the voiceprint
 * profile APIs used by {@link VoiceEnrollActivity}.
 */
public final class VoiceVaultApi {

    public static final String BASE_URL = "https://qq-box.tail580136.ts.net:3443";
    public static final String TRANSCRIBE_URL = BASE_URL + "/api/transcribe";
    public static final String HISTORY_URL = BASE_URL + "/api/history?limit=50";
    public static final String PROFILE_URL = BASE_URL + "/api/profile";
    public static final String PROFILE_STATUS_URL = BASE_URL + "/api/profile/status";
    public static final String PROFILE_ENROLL_URL = BASE_URL + "/api/profile/enroll";
    public static final String PROFILE_VERIFY_URL = BASE_URL + "/api/profile/verify";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 120000;

    private VoiceVaultApi() {
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static JSONObject parseResponse(HttpURLConnection connection) throws IOException, JSONException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readBody(stream);
        if (body == null || body.isEmpty()) {
            throw new IOException("HTTP " + code + " with empty response body");
        }
        JSONObject json = new JSONObject(body);
        if (code < 200 || code >= 300 || !json.optBoolean("ok", false)) {
            throw new IOException(json.optString("error", "HTTP " + code));
        }
        return json;
    }

    public static JSONObject request(String endpoint, String method) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (!"GET".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
        }
        try {
            return parseResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** POST a list of audio files as multipart/form-data parts named `field`. */
    public static JSONObject postAudioFiles(String endpoint, List<File> files, String field)
            throws IOException, JSONException {
        String boundary = "----VoiceVaultBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setChunkedStreamingMode(0);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try {
            try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                byte[] buffer = new byte[8192];
                for (File file : files) {
                    if (file == null || !file.exists() || file.length() == 0) continue;
                    out.writeBytes("--" + boundary + "\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"" + field + "\"; filename=\""
                            + file.getName() + "\"\r\n");
                    out.writeBytes("Content-Type: audio/mp4\r\n\r\n");
                    try (FileInputStream in = new FileInputStream(file)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    out.writeBytes("\r\n");
                }
                out.writeBytes("--" + boundary + "--\r\n");
                out.writeBytes("\r\n");
                out.flush();
            }
            return parseResponse(connection);
        } finally {
            connection.disconnect();
        }
    }
}
