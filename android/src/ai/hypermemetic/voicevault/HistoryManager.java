package ai.hypermemetic.voicevault;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class HistoryManager {
    private static final String TAG = "HistoryManager";
    private static final String FILE_NAME = "transcripts_history.json";
    private static final int MAX_TRANSCRIPTS = 50;
    private static final int MAX_LOCAL_RECORDINGS = 20;

    public static class Entry {
        public String id;
        public long timestamp;
        public long durationMs;
        public String transcript;

        public Entry(String id, long timestamp, long durationMs, String transcript) {
            this.id = id;
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.transcript = transcript != null ? transcript.trim() : "";
        }
    }

    public interface HistoryCallback {
        void onLoaded(Map<String, List<Entry>> grouped);
    }

    public static synchronized void saveLocalTranscript(Context context, String text, long durationMs) {
        if (text == null || text.trim().isEmpty()) return;
        List<Entry> entries = loadLocalCache(context);
        Entry newEntry = new Entry(
                "local_" + System.currentTimeMillis(),
                System.currentTimeMillis(),
                durationMs,
                text.trim()
        );
        entries.add(0, newEntry);
        while (entries.size() > MAX_TRANSCRIPTS) {
            entries.remove(entries.size() - 1);
        }
        writeLocalCache(context, entries);
    }

    public static synchronized List<Entry> loadLocalCache(Context context) {
        List<Entry> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return list;

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String text = obj.optString("transcript", "");
                if (!text.isEmpty()) {
                    list.add(new Entry(
                            obj.optString("id", String.valueOf(i)),
                            obj.optLong("timestamp", System.currentTimeMillis()),
                            obj.optLong("durationMs", 0),
                            text
                    ));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading local history cache", e);
        }
        return list;
    }

    private static synchronized void writeLocalCache(Context context, List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject obj = new JSONObject();
                obj.put("id", e.id);
                obj.put("timestamp", e.timestamp);
                obj.put("durationMs", e.durationMs);
                obj.put("transcript", e.transcript);
                arr.put(obj);
            }
            File file = new File(context.getFilesDir(), FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed writing local history cache", e);
        }
    }

    public static void fetchHistory(Context context, HistoryCallback callback) {
        new Thread(() -> {
            // First load local cache for instant UI rendering
            List<Entry> local = loadLocalCache(context);
            Map<String, List<Entry>> grouped = groupEntries(local);
            callback.onLoaded(grouped);

            // Then fetch latest 50 from server to sync
            try {
                URL url = new URL("https://qq-box.tail580136.ts.net:3443/api/history?limit=50");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);

                if (conn.getResponseCode() == 200) {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray recs = root.optJSONArray("recordings");
                    if (recs != null) {
                        List<Entry> remoteEntries = new ArrayList<>();
                        SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

                        for (int i = 0; i < recs.length(); i++) {
                            JSONObject item = recs.getJSONObject(i);
                            String text = item.optString("transcript", "").trim();
                            if (text.isEmpty()) continue; // Transcripts only

                            String createdAt = item.optString("created_at", "");
                            long ts = System.currentTimeMillis();
                            try {
                                if (createdAt.length() >= 19) {
                                    Date d = isoFmt.parse(createdAt.substring(0, 19));
                                    if (d != null) ts = d.getTime();
                                }
                            } catch (Exception ignored) {}

                            remoteEntries.add(new Entry(
                                    item.optString("id", ""),
                                    ts,
                                    item.optLong("duration_ms", 0),
                                    text
                            ));
                            if (remoteEntries.size() >= MAX_TRANSCRIPTS) break;
                        }

                        if (!remoteEntries.isEmpty()) {
                            writeLocalCache(context, remoteEntries);
                            callback.onLoaded(groupEntries(remoteEntries));
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Network history fetch skipped: " + e.getMessage());
            }
        }).start();
    }

    public static Map<String, List<Entry>> groupEntries(List<Entry> entries) {
        // Sort descending by timestamp
        Collections.sort(entries, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        Calendar today = Calendar.getInstance();
        Calendar cal = Calendar.getInstance();

        SimpleDateFormat dayFmt = new SimpleDateFormat("MMMM d, yyyy", Locale.US);

        for (Entry e : entries) {
            cal.setTimeInMillis(e.timestamp);
            String groupKey;
            if (isSameDay(today, cal)) {
                groupKey = "TODAY";
            } else {
                Calendar yesterday = (Calendar) today.clone();
                yesterday.add(Calendar.DAY_OF_YEAR, -1);
                if (isSameDay(yesterday, cal)) {
                    groupKey = "YESTERDAY";
                } else {
                    groupKey = dayFmt.format(new Date(e.timestamp)).toUpperCase(Locale.US);
                }
            }

            if (!groups.containsKey(groupKey)) {
                groups.put(groupKey, new ArrayList<>());
            }
            groups.get(groupKey).add(e);
        }
        return groups;
    }

    private static boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    public static void pruneLocalRecordings(Context context) {
        try {
            File dir = context.getExternalFilesDir("recordings");
            if (dir == null) dir = context.getFilesDir();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".m4a") || name.endsWith(".mp4"));
            if (files != null && files.length > MAX_LOCAL_RECORDINGS) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = MAX_LOCAL_RECORDINGS; i < files.length; i++) {
                    files[i].delete();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error pruning local audio recordings", e);
        }
    }
}
