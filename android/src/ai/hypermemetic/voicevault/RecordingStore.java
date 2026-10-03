package ai.hypermemetic.voicevault;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** App-private index and narrowly owned audio. No production content is logged. */
final class RecordingStore {
    private static RecordingStore instance;
    final RecordingIndex index;
    private final Context context;
    private RecordingStore(Context context) throws IOException {
        this.context=context.getApplicationContext();
        AtomicFile current=new AtomicFile(new File(context.getFilesDir(),"recording-index.json"));
        AtomicFile previous=new AtomicFile(new File(context.getFilesDir(),"recording-index.previous.json"));
        index=new RecordingIndex(new RecordingIndex.Disk() {
            public byte[] read() throws IOException {
                try { byte[] bytes=current.readFully(); RecordingIndex.validate(bytes); return bytes; }
                catch(IOException invalid) {
                    try { byte[] bytes=previous.readFully(); RecordingIndex.validate(bytes); return bytes; }
                    catch(IOException noBackup) {
                        if(current.getBaseFile().exists() || previous.getBaseFile().exists()) throw new IOException("Recording index unavailable");
                        return null;
                    }
                }
            }
            public void write(byte[] bytes) throws IOException {
                byte[] old=read(); if(old!=null) atomicWrite(previous,old); atomicWrite(current,bytes);
            }
        });
        index.restart(); reconcile(null);
    }
    static synchronized RecordingStore get(Context context) throws IOException {
        if(instance==null)instance=new RecordingStore(context); return instance;
    }
    private static void atomicWrite(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream out=null;
        try { out=file.startWrite(); out.write(bytes); out.getFD().sync(); file.finishWrite(out);
            if(!java.util.Arrays.equals(file.readFully(),bytes))throw new IOException("Recording storage failed"); }
        catch(IOException error) { if(out!=null)file.failWrite(out); throw new IOException("Recording storage failed"); }
    }
    static boolean family(String name) { return name!=null && name.matches("dictation_[0-9]{10,17}\\.(m4a|mp4)"); }
    private File directory(String location) {
        if(location.equals("external"))return context.getExternalFilesDir("recordings");
        if(location.equals("private"))return context.getFilesDir();
        if(location.equals("cache"))return new File(context.getCacheDir(),"recording-audio");
        return null;
    }
    private File owned(RecordingIndex.Entry e) {
        if(e==null || !(family(e.filename) ||
            (e.location.equals("cache") && RecordingIndex.validId(e.id) && e.filename.matches("server_"+e.id+"\\.(m4a|wav|webm|aac|mp3|ogg)"))))return null;
        File dir=directory(e.location); if(dir==null)return null;
        File file=new File(dir,e.filename);
        try {
            // A canonical mismatch rejects symlinks as well as traversal.
            if(!file.getCanonicalPath().equals(new File(dir.getCanonicalFile(),e.filename).getPath()))return null;
            return file.isFile() ? file : null;
        } catch(IOException error) { return null; }
    }
    private File cached(RecordingIndex.Entry e) {
        if(e==null || !RecordingIndex.validId(e.id) || !e.cachedFilename.matches("server_"+e.id+"\\.(m4a|wav|webm|aac|mp3|ogg)"))return null;
        File dir=new File(context.getCacheDir(),"recording-audio"),file=new File(dir,e.cachedFilename);
        try {return file.getCanonicalPath().equals(new File(dir.getCanonicalFile(),e.cachedFilename).getPath()) && file.isFile() ? file : null;}
        catch(IOException error){return null;}
    }
    File audio(RecordingIndex.Entry e) {
        if(e==null || e.pruned)return null;File file=owned(e);
        if(file!=null && file.length()>0 && duration(file)>=0)return file;
        file=cached(e);return file!=null && file.length()>0 ? file : null;
    }
    boolean usable(RecordingIndex.Entry e) { return e!=null && e.finalized && audio(e)!=null && duration(audio(e))>=0; }
    private static long duration(File file) {
        MediaMetadataRetriever media=new MediaMetadataRetriever();
        try { media.setDataSource(file.getAbsolutePath()); String value=media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value==null ? -1 : Long.parseLong(value); }
        catch(Exception invalid) { return -1; } finally { try { media.release(); }catch(Exception ignored){} }
    }
    synchronized RecordingIndex.Entry capture(File file,long time) throws IOException {
        String location="private"; File external=directory("external");
        if(external!=null && file.getParentFile().getCanonicalFile().equals(external.getCanonicalFile()))location="external";
        if(!family(file.getName()))throw new IOException("Invalid recording");
        return index.capture(time,location,file.getName());
    }
    synchronized RecordingIndex.Entry finalizeCapture(String id,long duration) throws IOException {
        RecordingIndex.Entry e=index.get(id); File file=audio(e);
        if(file==null || duration(file)<0)throw new IOException("Recording audio unavailable");
        try(FileOutputStream out=new FileOutputStream(file,true)) { out.getFD().sync(); }
        return index.finalizeCapture(id,duration);
    }
    synchronized void reconcile(String activeId) throws IOException {
        if((activeId==null || activeId.isEmpty()) && !VoiceVaultService.isRecording() && !VoiceVaultService.isProcessing())index.restart();
        for(String location:new String[]{"external","private"}) {
            File dir=directory(location); if(dir==null)continue;
            File[] files=dir.listFiles((d,name)->family(name)); if(files==null)continue;
            for(File file:files) {
                RecordingIndex.Entry known=null;for(RecordingIndex.Entry e:index.all())if(e.location.equals(location) && e.filename.equals(file.getName())){known=e;break;}
                if(known!=null) {
                    if(!known.deleted && !known.finalized && known.state.equals("interrupted") && !known.id.equals(activeId)) {
                        File original=audio(known);long duration=original==null ? -1 : duration(original);
                        if(duration>=0) {
                            try(FileOutputStream out=new FileOutputStream(original,true)){out.getFD().sync();}
                            known.finalized=true;if(known.durationMs==0)known.durationMs=duration;index.save(known);
                        }
                    }continue;
                }
                RecordingIndex.Entry probe=new RecordingIndex.Entry(); probe.location=location; probe.filename=file.getName();
                File owned=owned(probe); if(owned==null && file.length()>0)continue; // Reject symlinks, including broken links.
                try { if(!file.getCanonicalPath().equals(new File(dir.getCanonicalFile(),file.getName()).getPath()))continue; }catch(IOException error){continue;}
                long time=file.lastModified(); try { time=Long.parseLong(file.getName().substring(10,file.getName().lastIndexOf('.'))); }catch(Exception ignored){}
                long duration=owned==null ? -1 : duration(owned);
                index.adopt(time,Math.max(0,duration),location,file.getName(),duration>=0);
            }
        }
    }
    synchronized void prune() throws IOException {
        List<RecordingIndex.Entry> resolved=new ArrayList<>();
        for(RecordingIndex.Entry e:index.all())if(!e.deleted && e.resolved() && !e.pruned && audio(e)!=null)resolved.add(e);
        resolved.sort((a,b)->Long.compare(b.capturedAt,a.capturedAt));
        for(int i=20;i<resolved.size();i++) {
            RecordingIndex.Entry e=resolved.get(i); e.pruned=true; index.save(e);
            File original=owned(e),copy=cached(e);boolean removed=true;
            for(File file:new File[]{original,copy})if(file!=null && !file.delete() && file.exists())removed=false;
            if(!removed){e.pruned=false;index.save(e);}
        }
    }
    synchronized RecordingIndex.Entry deleteLocal(String id) throws IOException {
        RecordingIndex.Entry e=index.tombstone(id); if(e==null)return null;
        for(File file:new File[]{owned(e),cached(e)})if(file!=null && !file.delete())throw new IOException("Recording cleanup incomplete");
        return e;
    }
    synchronized RecordingIndex.Entry remote(String id,long time,long duration,String state,String failure,String text,boolean audioAvailable) throws IOException {
        if(!RecordingIndex.validId(id) || suppressed(id))return null;
        RecordingIndex.Entry e=null;
        for(RecordingIndex.Entry candidate:index.all())if(candidate.id.equals(id) || candidate.serverId.equals(id)) { e=candidate; break; }
        if(e==null) { e=new RecordingIndex.Entry(); e.id=id; e.serverId=id; e.capturedAt=time; e.durationMs=duration; e.finalized=true; }
        if(!e.state.equals("recording") && !e.state.equals("processing") && (!e.resolved() || state.matches("transcribed|no_speech|speaker_rejected"))) {
            if(state.matches("pending|failed|interrupted|transcribed|no_speech|speaker_rejected"))e.state=state;
            else e.state="interrupted";
            e.failure=failure.isEmpty() ? "" : RecordingIndex.safeCategory(failure); e.transcript=text;
        }
        e.serverId=id; e.serverAudio=audioAvailable; index.save(e); return e;
    }
    synchronized void cleanupFinished(String id,boolean done) throws IOException {
        RecordingIndex.Entry e=index.get(id); if(e==null || !e.deleted)return; e.cleanupPending=!done; index.save(e);
    }
    synchronized boolean suppressed(String id) {
        for(RecordingIndex.Entry e:index.all())if(e.deleted && (e.id.equals(id) || e.serverId.equals(id)))return true; return false;
    }
}
