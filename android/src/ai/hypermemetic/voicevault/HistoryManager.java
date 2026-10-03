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
        public String localId = "", serverId = "", status = "transcribed", failure = "";
        public boolean audioAvailable, localAvailable, cleanupPending;

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

    public interface LatestTranscriptCallback {
        void onLoaded(Entry latest);
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
            Log.e(TAG, "History cache unavailable");
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
            Log.e(TAG, "History cache storage failure");
        }
    }

    private static Entry recordingEntry(RecordingStore store,RecordingIndex.Entry e) {
        Entry out=new Entry(e.id,e.capturedAt,e.durationMs,e.transcript);out.localId=e.id;out.serverId=e.serverId;
        out.status=e.state;out.failure=e.failure;out.localAvailable=store.usable(e);
        out.audioAvailable=out.localAvailable || e.serverAudio;out.cleanupPending=e.deleted && e.cleanupPending;
        if(out.cleanupPending) {out.status="cleanup_incomplete";out.audioAvailable=false;}return out;
    }
    static List<Entry> merge(Context context,List<Entry> legacy,List<Entry> remote) {
        Map<String,Entry> merged=new LinkedHashMap<>();RecordingStore store=null;
        try {store=RecordingStore.get(context);store.reconcile(VoiceVaultService.getActiveRecordingId());
            for(Entry r:remote)store.remote(r.id,r.timestamp,r.durationMs,r.status,r.failure,r.transcript,r.audioAvailable);
            for(RecordingIndex.Entry e:store.index.all())if(!e.deleted || e.cleanupPending)merged.put(e.id,recordingEntry(store,e));
        }catch(Exception error){Log.e(TAG,"Recording history storage failure");}
        for(Entry e:legacy) {
            if(store!=null && store.suppressed(e.id))continue;
            boolean known=merged.containsKey(e.id);for(Entry stored:merged.values())if(!stored.serverId.isEmpty() && stored.serverId.equals(e.id))known=true;
            if(!known)merged.put(e.id,e);
        }
        if(store==null)for(Entry e:remote)merged.putIfAbsent(e.id,e);
        List<Entry> sorted=new ArrayList<>(merged.values());sorted.sort((a,b)->Long.compare(b.timestamp,a.timestamp));
        List<Entry> visible=new ArrayList<>();int resolved=0;
        for(Entry e:sorted) {
            boolean terminal=e.status.equals("transcribed") || e.status.equals("no_speech") || e.status.equals("speaker_rejected");
            if(!terminal || resolved++<MAX_TRANSCRIPTS)visible.add(e);
        }return visible;
    }
    public static void fetchHistory(Context context,HistoryCallback callback) {
        new Thread(()->{
            List<Entry> legacy=loadLocalCache(context);callback.onLoaded(groupEntries(merge(context,legacy,new ArrayList<>())));
            List<Entry> remote=fetchRemoteEntries();callback.onLoaded(groupEntries(merge(context,legacy,remote)));
            // Only resolved transcript cache is bounded; durable unresolved entries live independently.
            List<Entry> texts=new ArrayList<>();for(Entry e:merge(context,legacy,remote))if(!e.transcript.isEmpty() && !e.cleanupPending && texts.size()<MAX_TRANSCRIPTS)texts.add(e);
            writeLocalCache(context,texts);
        }).start();
    }
    public static void fetchLatestTranscript(Context context,LatestTranscriptCallback callback) {
        new Thread(()->{
            List<Entry> legacy=loadLocalCache(context);Entry latest=latestEntry(merge(context,legacy,new ArrayList<>()));
            if(latest==null)latest=latestEntry(merge(context,legacy,fetchRemoteEntries()));
            if(callback!=null)callback.onLoaded(latest);
        }).start();
    }

    /** Newest non-blank entry (by timestamp), or null when the list has none. */
    public static Entry latestEntry(List<Entry> entries) {
        Entry latest = null;
        if (entries == null) return null;
        for (Entry entry : entries) {
            if (entry == null || entry.transcript == null || entry.transcript.trim().isEmpty()) continue;
            if (latest == null || entry.timestamp > latest.timestamp) {
                latest = entry;
            }
        }
        return latest;
    }

    private static List<Entry> fetchRemoteEntries() {
        List<Entry> remoteEntries = new ArrayList<>();
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
                    SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                    isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

                    for (int i = 0; i < recs.length(); i++) {
                        JSONObject item = recs.getJSONObject(i);
                        String text = item.optString("transcript", "").trim();
                        // Blank text is a valid unresolved or terminal no-speech item.

                        String createdAt = item.optString("created_at", "");
                        long ts = System.currentTimeMillis();
                        try {
                            if (createdAt.length() >= 19) {
                                Date d = isoFmt.parse(createdAt.substring(0, 19));
                                if (d != null) ts = d.getTime();
                            }
                        } catch (Exception ignored) {}

                        Entry entry=new Entry(item.optString("id",""),ts,item.optLong("duration_ms",0),text);
                        entry.serverId=entry.id;entry.status=item.optString("status","transcribed");
                        entry.failure=item.optString("error_category","");entry.audioAvailable=item.optBoolean("audio_available",!entry.id.isEmpty());
                        remoteEntries.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "History server unavailable");
        }
        return remoteEntries;
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
        try { RecordingStore.get(context).prune(); }
        catch(Exception error){Log.e(TAG,"Recording retention storage failure");}
    }
    static boolean delete(Context context,Entry selected) {
        try {
            RecordingStore store=RecordingStore.get(context);RecordingIndex.Entry e=store.index.get(selected.id);
            if(e==null) {
                e=new RecordingIndex.Entry();e.id=selected.id;e.capturedAt=selected.timestamp;e.durationMs=selected.durationMs;
                e.transcript=selected.transcript;e.state=selected.status;e.serverId=selected.serverId;store.index.save(e);
            }
            e=store.deleteLocal(e.id);if(e==null)return false;
            boolean cleaned=true;
            String remote=e.serverId;
            // Updated uploads always use the local UUID, even when their response was lost.
            if(remote.isEmpty() && !e.filename.isEmpty())remote=e.id;
            if(!remote.isEmpty()) {
                DictationUpload.Response response=new DictationUpload().request(VoiceVaultApi.BASE_URL+"/api/recording/"+remote,"DELETE",null,0,null,15000,30000);
                cleaned=response.status==200;
                if(cleaned) {Object parsed=RecordingIndex.Json.read(response.body);cleaned=parsed instanceof Map && Boolean.TRUE.equals(((Map<?,?>)parsed).get("ok"));}
            }
            store.cleanupFinished(e.id,cleaned);return cleaned;
        }catch(Exception error){Log.e(TAG,"Recording cleanup incomplete");return false;}
    }
    static String statusLabel(Entry e) {
        if(e.cleanupPending)return "Deletion incomplete — retry cleanup";
        if(!e.audioAvailable && e.transcript.isEmpty())return "Audio unavailable · "+e.status.replace('_',' ');
        if(e.status.equals("recovered"))return "Recovered audio · previous outcome unknown";
        if(e.status.equals("failed") || e.status.equals("interrupted"))return "Saved · "+e.status+" · "+RecordingIndex.safeCategory(e.failure).replace('_',' ');
        if(e.status.equals("speaker_rejected"))return "Other speaker rejected";
        if(e.status.equals("no_speech"))return "No speech";
        if(e.status.equals("processing"))return "Processing";
        if(e.status.equals("recording"))return "Recording";
        return e.status.equals("transcribed") ? "Transcribed" : "Saved · "+e.status;
    }
}
