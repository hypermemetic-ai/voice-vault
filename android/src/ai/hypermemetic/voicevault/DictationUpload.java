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
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;

/** One recording's upload; cancellation never blocks the UI thread. */
final class DictationUpload {
    private volatile boolean canceled;
    private volatile HttpURLConnection connection;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final String DEVICE = "Pixel 10 Native";

    static final class Failure extends IOException {
        final String feedback, category;
        final int status;
        String recordingId="";boolean audioAvailable;
        Failure(String feedback) { this(feedback,"invalid_response",0); }
        Failure(String feedback,String category,int status) {
            super(feedback);this.feedback=feedback;this.category=RecordingIndex.safeCategory(category);this.status=status;
        }
    }

    void cancel() { canceled = true; }

    /** Call on a worker: some implementations lock disconnect during blocking I/O. */
    void disconnect() {
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    private void checkCanceled() throws IOException {
        if (canceled) throw new Failure("Transcription canceled","canceled",0);
    }

    String post(String endpoint, File audio, long durationMs) throws IOException {
        return post(endpoint, audio, durationMs, 30000, 600000);
    }

    String post(String endpoint,File audio,long durationMs,String identity) throws IOException {
        Response response=request(endpoint,"POST",audio,durationMs,identity,30000,600000);
        return accepted(response);
    }
    String post(String endpoint, File audio, long durationMs, int connectMs, int readMs) throws IOException {
        return accepted(request(endpoint,"POST",audio,durationMs,null,connectMs,readMs));
    }
    static final class Response {
        final int status; final String body;
        Response(int status,String body){this.status=status;this.body=body;}
    }
    Response request(String endpoint,String method,File audio,long durationMs,String identity,int connectMs,int readMs) throws IOException {
        checkCanceled();
        if ((audio!=null && (!audio.isFile() || audio.length()==0)) || (method.equals("POST") && endpoint.endsWith("/api/transcribe") && audio==null)) {
            throw new Failure("No recording audio — try again","audio_unavailable",0);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        connection = conn;
        try {
            checkCanceled();
            conn.setRequestMethod(method);
            conn.setDoOutput(audio!=null);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            if(audio!=null)conn.setRequestProperty("Content-Type", audio.getName().endsWith(".wav") ? "audio/wav" : "audio/mp4");
            conn.setRequestProperty("X-Duration-Ms", String.valueOf(durationMs));
            conn.setRequestProperty("X-Device", DEVICE);
            if(identity!=null && RecordingIndex.validId(identity))conn.setRequestProperty("X-Recording-Id",identity);
            if(audio!=null) {
            conn.setFixedLengthStreamingMode(audio.length());
            try (OutputStream out = conn.getOutputStream(); FileInputStream in = new FileInputStream(audio)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCanceled();
                    out.write(buffer, 0, read);
                }
            }
            }
            checkCanceled();
            return read(conn);
        } finally {
            conn.disconnect();
            connection = null;
        }
    }

    /** One PCM append while recording; no response body is needed beyond the JSON. */
    Response append(String endpoint, byte[] pcm) throws IOException {
        return bytes(endpoint, pcm, "application/octet-stream", 15000, 30000);
    }

    /** Verified finish: the server checks these bytes against its stream. */
    Response finish(String endpoint, long bytes, String sha256, long durationMs) throws IOException {
        String body = "{\"bytes\":" + bytes + ",\"sha256\":\"" + sha256 + "\",\"durationMs\":" + durationMs
                + ",\"device\":\"" + DEVICE + "\"}";
        return bytes(endpoint, body.getBytes(StandardCharsets.UTF_8), "application/json", 30000, 600000);
    }

    private Response bytes(String endpoint, byte[] body, String contentType, int connectMs, int readMs) throws IOException {
        checkCanceled();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        connection = conn;
        try {
            checkCanceled();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            checkCanceled();
            return read(conn);
        } finally {
            conn.disconnect();
            connection = null;
        }
    }

    private Response read(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream stream=status>=400 ? conn.getErrorStream() : conn.getInputStream();
        if(stream==null)return new Response(status,"");
        try (InputStream in = stream; ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkCanceled();
                if (body.size() + read > MAX_RESPONSE_BYTES) throw new Failure("Invalid server response");
                body.write(buffer, 0, read);
            }
            checkCanceled();
            return new Response(status,body.toString("UTF-8"));
        }
    }

    /** SHA-256 of the complete local WAV, the server's finish fingerprint. */
    static String sha256(File file) throws IOException {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex=new StringBuilder();
            for(byte value:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",value));
            return hex.toString();
        } catch(java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("Hash unavailable");
        }
    }

    static String accepted(Response response) throws IOException {
        if(response.status!=200) {
            String category="server_error",recordingId="";boolean audioAvailable=false;
            try { Object parsed=RecordingIndex.Json.read(response.body);if(parsed instanceof java.util.Map) {
                java.util.Map<?,?> data=(java.util.Map<?,?>)parsed;Object value=data.get("errorCategory");if(value instanceof String)category=RecordingIndex.safeCategory((String)value);
                Object id=data.get("id");if(id instanceof String && RecordingIndex.validId((String)id))recordingId=(String)id;
                audioAvailable=Boolean.TRUE.equals(data.get("audioAvailable"));
            }}catch(Exception ignored){}
            Failure failure=new Failure(category.equals("storage_full") ? "Server storage full — recording retained" : "Server error (HTTP "+response.status+")",category,response.status);
            failure.recordingId=recordingId;failure.audioAvailable=audioAvailable;throw failure;
        }return response.body;
    }
    static String category(Exception error) {
        if(error instanceof Failure)return ((Failure)error).category;
        if(error instanceof SocketTimeoutException)return "timeout";
        if(error instanceof SSLException)return "secure_connection";
        if(error instanceof IOException)return "connection";return "invalid_response";
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
