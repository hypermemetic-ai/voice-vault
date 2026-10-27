package ai.hypermemetic.voicevault;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public final class DictationUploadHarness {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    interface Request { void run() throws Exception; }
    static void fails(Request request, String feedback) throws Exception {
        try { request.run(); throw new AssertionError("expected failure: " + feedback); }
        catch (java.io.IOException expected) {
            check(DictationUpload.feedback(expected).contains(feedback), "category: " + DictationUpload.feedback(expected));
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] synthetic = new byte[20000];
        Arrays.fill(synthetic, (byte) 42);
        File file = File.createTempFile("vv-synthetic-upload-", ".m4a");
        File wav = File.createTempFile("vv-synthetic-upload-", ".wav");
        Files.write(file.toPath(), synthetic);
        Files.write(wav.toPath(), synthetic);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/success", exchange -> {
            requests.incrementAndGet();
            check(Arrays.equals(exchange.getRequestBody().readAllBytes(), synthetic), "uploaded synthetic bytes");
            check("audio/mp4".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "content type");
            check("1234".equals(exchange.getRequestHeaders().getFirst("X-Duration-Ms")), "duration header");
            byte[] body = "{\"ok\":true,\"text\":\"synthetic\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/wav", exchange -> {
            check(Arrays.equals(exchange.getRequestBody().readAllBytes(), synthetic), "uploaded WAV bytes");
            check("audio/wav".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "WAV content type");
            byte[] body = "{\"ok\":true,\"text\":\"synthetic wav\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/stream", exchange -> {
            check("application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "PCM content type");
            check(Arrays.equals(exchange.getRequestBody().readAllBytes(), new byte[]{1,2,3,4}), "PCM body");
            byte[] body = "{\"ok\":true,\"received\":4}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/finish", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            check("application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "finish content type");
            check(request.contains("\"bytes\":4"), "finish bytes");
            check(request.contains("\"sha256\":\"synthetic-sha\""), "finish hash");
            check(request.contains("\"durationMs\":1234"), "finish duration");
            check(request.contains("\"device\":\"" + DictationUpload.DEVICE + "\""), "finish device");
            byte[] body = "{\"ok\":true,\"text\":\"synthetic finish\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "private response details never exposed".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = new byte[1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } catch (java.io.IOException ignored) {}
            exchange.close();
        });
        server.createContext("/hang", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            check(new DictationUpload().post(base + "/success", file, 1234).contains("synthetic"), "success response");
            check(new DictationUpload().post(base + "/wav", wav, 1234).contains("synthetic wav"), "WAV content type");
            DictationUpload.Response appended = new DictationUpload().append(base + "/stream?offset=0", new byte[]{1,2,3,4});
            check(appended.status == 200 && appended.body.contains("\"received\":4"), "PCM append response");
            DictationUpload.Response ended = new DictationUpload().finish(base + "/finish", 4, "synthetic-sha", 1234);
            check(ended.status == 200 && ended.body.contains("synthetic finish"), "finish response");
            check(DictationUpload.sha256(wav).matches("[0-9a-f]{64}"), "canonical WAV hash");
            check(requests.get() == 1, "one request");
            fails(() -> new DictationUpload().post(base + "/error", file, 1234), "HTTP 503");
            fails(() -> new DictationUpload().post(base + "/large", file, 1234), "Invalid server response");
            fails(() -> new DictationUpload().post(base + "/hang", file, 1234, 100, 100), "timed out");
            DictationUpload canceled = new DictationUpload();
            canceled.cancel();
            fails(() -> canceled.post(base + "/success", file, 1234), "canceled");
            check(requests.get() == 1, "canceled request not sent");
            fails(() -> new DictationUpload().post(base, new File(file.getParent(), "missing-synthetic-file"), 0), "No recording audio");
            check(DictationUpload.feedback(new UnknownHostException("PRIVATE host")).equals("Cannot reach server — check Tailscale"), "private DNS category");
            check(!DictationUpload.feedback(new SocketTimeoutException("PRIVATE path")).contains("PRIVATE"), "private timeout category");
            // Failure must not poison the next recording's independent request.
            check(new DictationUpload().post(base + "/success", file, 1234).contains("synthetic"), "retry success");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            file.delete();
            wav.delete();
        }
    }
}
