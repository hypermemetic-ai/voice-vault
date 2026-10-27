package ai.hypermemetic.voicevault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

/**
 * Best-effort PCM appends for one recording. It only ever reads the durable
 * local WAV, and runs on its own thread so a slow or failed request can never
 * block, slow or stop capture.
 */
final class RecordingStreamer implements Runnable {
    private static final long INTERVAL_MS = 3000L;
    private static final long MAX_BACKOFF_MS = 30000L;
    private static final int MAX_CHUNK = 4 * 1024 * 1024;

    private final String endpoint;
    private final File file;
    private final DictationUpload upload = new DictationUpload();
    private final Object lock = new Object();
    private volatile long written = 0;
    private volatile boolean recording = true;
    private volatile boolean canceled = false;
    private long sent = 0;
    private long backoff = INTERVAL_MS;
    private Thread thread;

    RecordingStreamer(String endpoint, File file) {
        this.endpoint = endpoint;
        this.file = file;
    }

    void start() {
        Thread next = new Thread(this, "VoiceVaultStream");
        thread = next;
        next.start();
    }

    /** Capture published another complete, even PCM byte count. */
    void written(long pcmBytes) {
        synchronized (lock) {
            if (pcmBytes > written) written = pcmBytes;
        }
    }

    /** Capture stopped; no more bytes will arrive. */
    void stop(long pcmBytes) {
        synchronized (lock) {
            if (pcmBytes > written) written = pcmBytes;
            recording = false;
            lock.notifyAll();
        }
    }

    /** Bounded wait for the tail; true when the server holds every byte. */
    boolean flush(long timeoutMs) {
        Thread waiting = thread;
        if (waiting == null) return false;
        try {
            waiting.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            return sent >= written;
        }
    }

    void cancel() {
        canceled = true;
        Thread active = thread;
        if (active != null) active.interrupt();
        synchronized (lock) { lock.notifyAll(); }
    }

    void disconnect() {
        upload.disconnect();
    }

    @Override
    public void run() {
        long next = System.currentTimeMillis() + INTERVAL_MS;
        while (!canceled) {
            long target;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                boolean finished = !recording;
                boolean due = sent < written && (finished || now >= next);
                if (!due && finished && sent >= written) break;
                if (!due && !finished && now >= next) next = now + INTERVAL_MS;
                if (!due) {
                    try { lock.wait(Math.max(1, next - now)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                target = written;
            }
            if (target <= sent) continue;
            if (!send(target)) {
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } else {
                backoff = INTERVAL_MS;
            }
            next = System.currentTimeMillis() + backoff;
        }
    }

    private boolean send(long target) {
        long offset;
        int length;
        synchronized (lock) {
            offset = sent;
            long available = target - offset;
            if (available <= 0) return true;
            length = (int) Math.min(available, MAX_CHUNK) & ~1;
            if (length <= 0) return true;
        }
        byte[] pcm = new byte[length];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(WavHeader.SIZE + offset);
            in.readFully(pcm);
        } catch (IOException unavailable) {
            return false;
        }
        try {
            DictationUpload.Response response = upload.append(endpoint + "?offset=" + offset, pcm);
            if (response.status == 200) {
                synchronized (lock) { sent = Math.max(offset + length, received(response.body)); }
                return true;
            }
            if (response.status == 409 && "stream_gap".equals(errorCategory(response.body))) {
                long held = received(response.body);
                if (held >= 0) {
                    synchronized (lock) { sent = held; }
                    return true;
                }
            }
        } catch (IOException ignored) {
            // Dropped connections are retried from the same offset.
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> json(String body) {
        try {
            Object value = RecordingIndex.Json.read(body);
            return value instanceof Map ? (Map<String,Object>) value : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static long received(String body) {
        Map<String,Object> data = json(body);
        Object value = data == null ? null : data.get("received");
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    private static String errorCategory(String body) {
        Map<String,Object> data = json(body);
        Object value = data == null ? null : data.get("errorCategory");
        return value instanceof String ? (String) value : "";
    }
}
