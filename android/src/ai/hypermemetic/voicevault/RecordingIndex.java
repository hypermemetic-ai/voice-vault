package ai.hypermemetic.voicevault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable recording transitions. Writes commit before callers can observe new state. */
final class RecordingIndex {
    interface Disk { byte[] read() throws IOException; void write(byte[] bytes) throws IOException; }
    static final class Entry {
        String id = "", serverId = "", location = "", filename = "", cachedFilename = "", state = "recovered", failure = "", transcript = "";
        long capturedAt, durationMs, attempt;
        boolean finalized, pruned, deleted, cleanupPending, serverAudio;
        Entry copy() { Entry e = new Entry(); e.id=id; e.serverId=serverId; e.location=location; e.filename=filename;
            e.cachedFilename=cachedFilename;e.state=state; e.failure=failure; e.transcript=transcript; e.capturedAt=capturedAt; e.durationMs=durationMs;
            e.attempt=attempt; e.finalized=finalized; e.pruned=pruned; e.deleted=deleted; e.cleanupPending=cleanupPending; e.serverAudio=serverAudio; return e; }
        boolean resolved() { return state.equals("transcribed") || state.equals("no_speech") || state.equals("speaker_rejected"); }
    }
    private final Disk disk;
    private Map<String, Entry> entries;
    RecordingIndex(Disk disk) throws IOException { this.disk=disk; entries=decode(disk.read()); }
    synchronized List<Entry> all() { List<Entry> out=new ArrayList<>(); for(Entry e:entries.values()) out.add(e.copy()); return out; }
    synchronized Entry get(String id) { Entry e=entries.get(id); return e==null ? null : e.copy(); }
    synchronized void save(Entry entry) throws IOException {
        Map<String, Entry> next=new LinkedHashMap<>(entries); next.put(entry.id, entry.copy());
        disk.write(encode(next)); entries=next;
    }
    synchronized Entry capture(long time, String location, String filename) throws IOException {
        Entry e=new Entry(); e.id=UUID.randomUUID().toString(); e.capturedAt=time; e.location=location; e.filename=filename; e.state="recording"; save(e); return e;
    }
    synchronized Entry adopt(long time, long duration, String location, String filename, boolean usable) throws IOException {
        for(Entry e:entries.values()) if(e.location.equals(location) && e.filename.equals(filename)) return e.copy();
        Entry e=new Entry(); e.id=UUID.randomUUID().toString(); e.capturedAt=time; e.durationMs=duration; e.location=location;
        e.filename=filename; e.finalized=usable; e.failure=usable ? "outcome_unknown" : "audio_unavailable"; save(e); return e;
    }
    synchronized Entry finalizeCapture(String id, long duration) throws IOException {
        Entry e=require(id); if(!e.state.equals("recording")) throw new IOException("Invalid capture state");
        e.durationMs=Math.max(0,duration); e.finalized=true; e.state="pending"; e.failure=""; save(e); return e;
    }
    synchronized Entry begin(String id) throws IOException {
        Entry e=require(id); if(e.deleted || !e.finalized || e.pruned || e.state.equals("recording") || e.state.equals("processing") || e.resolved()) return null;
        e.attempt++; e.state="processing"; e.failure=""; save(e); return e;
    }
    synchronized boolean finish(String id, long attempt, String state, String failure, String text, String serverId) throws IOException {
        Entry e=require(id); if(e.deleted || !e.state.equals("processing") || e.attempt!=attempt) return false;
        e.state=state; e.failure=safeCategory(failure); e.transcript=text==null ? "" : text.trim();
        if(serverId!=null && validId(serverId)) {e.serverId=serverId;if(e.resolved())e.serverAudio=true;} save(e); return true;
    }
    synchronized void serverCopy(String id,long attempt,String serverId,boolean available) throws IOException {
        Entry e=require(id);if(e.deleted || !e.state.equals("processing") || e.attempt!=attempt || !validId(serverId))return;
        e.serverId=serverId;e.serverAudio=available;save(e);
    }
    synchronized void interrupt(String id, String reason) throws IOException {
        Entry e=require(id); if(e.deleted || !(e.state.equals("recording") || e.state.equals("pending") || e.state.equals("processing"))) return;
        e.attempt++; e.state="interrupted"; e.failure=safeCategory(reason); save(e);
    }
    synchronized void restart() throws IOException {
        for(Entry e:all()) if(!e.deleted && (e.state.equals("recording") || e.state.equals("processing") || e.state.equals("pending"))) {
            e.attempt++; e.state="interrupted"; e.failure="interrupted"; save(e);
        }
    }
    synchronized Entry tombstone(String id) throws IOException {
        Entry e=require(id); if(e.state.equals("recording") || e.state.equals("processing")) return null;
        e.deleted=true; e.cleanupPending=true; e.attempt++; save(e); return e;
    }
    private Entry require(String id) throws IOException { Entry e=get(id); if(e==null) throw new IOException("Unknown recording"); return e; }
    static boolean validId(String id) { return id!=null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"); }
    static String safeCategory(String reason) {
        if("".equals(reason))return "";
        if(reason!=null && reason.matches("storage_full|storage_failure|invalid_audio|audio_unavailable|timeout|connection|secure_connection|server_error|invalid_response|backend_failed|processing_failed|interrupted|canceled|outcome_unknown|busy|identity_conflict|cleanup_incomplete")) return reason;
        return "processing_failed";
    }
    private static byte[] encode(Map<String,Entry> entries) {
        List<Object> rows=new ArrayList<>();
        for(Entry e:entries.values()) {
            Map<String,Object> row=new LinkedHashMap<>(); row.put("id",e.id); row.put("serverId",e.serverId); row.put("capturedAt",e.capturedAt);
            row.put("durationMs",e.durationMs); row.put("location",e.location); row.put("filename",e.filename); row.put("state",e.state);
            row.put("cachedFilename",e.cachedFilename);row.put("failure",e.failure); row.put("transcript",e.transcript); row.put("attempt",e.attempt); row.put("finalized",e.finalized);
            row.put("pruned",e.pruned); row.put("deleted",e.deleted); row.put("cleanupPending",e.cleanupPending); row.put("serverAudio",e.serverAudio); rows.add(row);
        }
        Map<String,Object> root=new LinkedHashMap<>(); root.put("version",1L); root.put("recordings",rows);
        return Json.write(root).getBytes(StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Entry> decode(byte[] data) throws IOException {
        Map<String,Entry> out=new LinkedHashMap<>(); if(data==null || data.length==0) return out;
        try {
            Map<String,Object> root=(Map<String,Object>)Json.read(new String(data,StandardCharsets.UTF_8));
            if(!Long.valueOf(1).equals(root.get("version"))) throw new IOException("Unsupported recording index");
            for(Object item:(List<Object>)root.get("recordings")) {
                Map<String,Object> r=(Map<String,Object>)item; Entry e=new Entry(); e.id=(String)r.get("id");
                if(!validId(e.id) || out.containsKey(e.id)) throw new IOException("Invalid recording index");
                e.serverId=str(r,"serverId"); e.capturedAt=num(r,"capturedAt"); e.durationMs=num(r,"durationMs"); e.attempt=num(r,"attempt");
                e.location=str(r,"location"); e.filename=str(r,"filename"); e.cachedFilename=str(r,"cachedFilename"); e.state=str(r,"state"); e.failure=str(r,"failure"); e.transcript=str(r,"transcript");
                e.finalized=Boolean.TRUE.equals(r.get("finalized")); e.pruned=Boolean.TRUE.equals(r.get("pruned"));
                e.deleted=Boolean.TRUE.equals(r.get("deleted")); e.cleanupPending=Boolean.TRUE.equals(r.get("cleanupPending")); e.serverAudio=Boolean.TRUE.equals(r.get("serverAudio")); out.put(e.id,e);
            }
            return out;
        } catch(IOException e) { throw e; } catch(Exception e) { throw new IOException("Invalid recording index"); }
    }
    static void validate(byte[] data) throws IOException { decode(data); }
    private static String str(Map<String,Object> r,String key) { Object v=r.get(key); return v instanceof String ? (String)v : ""; }
    private static long num(Map<String,Object> r,String key) { Object v=r.get(key); return v instanceof Number ? ((Number)v).longValue() : 0; }

    /** Small JSON codec for the fixed private index; no external runtime dependency. */
    static final class Json {
        static String write(Object value) {
            if(value instanceof String) { StringBuilder b=new StringBuilder("\""); for(char c:((String)value).toCharArray()) {
                if(c=='"' || c=='\\') b.append('\\').append(c); else if(c<32) b.append(String.format(java.util.Locale.US,"\\u%04x",(int)c)); else b.append(c);
            } return b.append('"').toString(); }
            if(value instanceof Map) { StringBuilder b=new StringBuilder("{"); for(Object key:((Map<?,?>)value).keySet()) {
                if(b.length()>1)b.append(','); b.append(write(key.toString())).append(':').append(write(((Map<?,?>)value).get(key))); } return b.append('}').toString(); }
            if(value instanceof List) { StringBuilder b=new StringBuilder("["); for(Object v:(List<?>)value) { if(b.length()>1)b.append(','); b.append(write(v)); } return b.append(']').toString(); }
            if(value instanceof Number || value instanceof Boolean) return value.toString(); return "null";
        }
        final String text; int pos,depth;
        Json(String text) { this.text=text; }
        static Object read(String text) throws IOException { Json j=new Json(text); Object v=j.value(); j.space(); if(j.pos!=text.length())throw new IOException(); return v; }
        void space() { while(pos<text.length() && Character.isWhitespace(text.charAt(pos)))pos++; }
        char take() throws IOException { if(pos>=text.length())throw new IOException(); return text.charAt(pos++); }
        Object value() throws IOException {
            space(); char c=take();
            if(c=='"') { StringBuilder b=new StringBuilder(); for(;;) { char q=take(); if(q=='"')return b.toString(); if(q=='\\') {
                q=take(); switch(q) { case '"': case '\\': case '/': b.append(q); break; case 'n':b.append('\n');break; case 'r':b.append('\r');break;
                case 't':b.append('\t');break; case 'b':b.append('\b');break; case 'f':b.append('\f');break;
                case 'u': if(pos+4>text.length())throw new IOException(); try { b.append((char)Integer.parseInt(text.substring(pos,pos+4),16)); }catch(Exception e){throw new IOException();}pos+=4;break;
                default:throw new IOException(); }
            } else { if(q<32)throw new IOException(); b.append(q); } } }
            if(c=='{' || c=='[') {
                if(++depth>64)throw new IOException();
                Map<String,Object> map=new LinkedHashMap<>(); List<Object> list=new ArrayList<>(); space(); char end=c=='{' ? '}' : ']';
                if(pos<text.length() && text.charAt(pos)==end) { pos++; depth--;return c=='{' ? map : list; }
                for(;;) { if(c=='{') { Object key=value(); if(!(key instanceof String))throw new IOException(); space(); if(take()!=':')throw new IOException(); if(map.containsKey(key))throw new IOException();map.put((String)key,value()); } else list.add(value());
                    space(); char sep=take(); if(sep==end){depth--;return c=='{' ? map : list;} if(sep!=',')throw new IOException(); }
            }
            pos--; for(String literal:new String[]{"true","false","null"})if(text.startsWith(literal,pos)) { pos+=literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            int start=pos; while(pos<text.length() && "-+0123456789.eE".indexOf(text.charAt(pos))>=0)pos++;
            String number=text.substring(start,pos);if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IOException();
            try { if(number.indexOf('.')>=0 || number.indexOf('e')>=0 || number.indexOf('E')>=0)return Double.parseDouble(number);return Long.parseLong(number); }catch(Exception e){throw new IOException();}
        }
    }
}
