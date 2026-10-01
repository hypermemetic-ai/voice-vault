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
        Files.write(file.toPath(), synthetic);
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
        }
    }
}
