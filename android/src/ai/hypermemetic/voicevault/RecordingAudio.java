package ai.hypermemetic.voicevault;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Original audio retrieval is only invoked by an explicit playback/export action. */
final class RecordingAudio {
    static File prepare(Context context,String id) throws IOException {
        RecordingStore store=RecordingStore.get(context); RecordingIndex.Entry entry=store.index.get(id);
        if(entry==null || entry.deleted)throw new IOException("Audio unavailable");
        if(store.usable(entry))return store.audio(entry);
        if(!entry.serverAudio || !RecordingIndex.validId(entry.serverId))throw new IOException("Audio unavailable");
        File dir=new File(context.getCacheDir(),"recording-audio"); if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("Storage failed");
        HttpURLConnection connection=(HttpURLConnection)new URL(VoiceVaultApi.BASE_URL+"/api/audio/"+entry.serverId).openConnection();
        File temporary=new File(dir,"fetch_"+id+".part");
        try {
            connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
            if(connection.getResponseCode()!=200)throw new IOException("Audio unavailable");
            String type=connection.getContentType(); String ext="m4a";
            if(type!=null) { if(type.contains("wav"))ext="wav";else if(type.contains("webm"))ext="webm";else if(type.contains("ogg"))ext="ogg";else if(type.contains("mpeg"))ext="mp3";else if(type.contains("aac"))ext="aac"; }
            long deadline=System.nanoTime()+120000000000L, total=0;
            try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(temporary)) {
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1) { total+=count;if(total>100L*1024*1024 || System.nanoTime()>deadline)throw new IOException("Audio retrieval failed");out.write(buffer,0,count); }
                if(total==0)throw new IOException("Audio unavailable");out.getFD().sync();
            }
            synchronized(store) {
                RecordingIndex.Entry current=store.index.get(id); if(current==null || current.deleted)throw new IOException("Audio unavailable");
                File file=new File(dir,"server_"+id+"."+ext); if(!temporary.renameTo(file))throw new IOException("Storage failed");
                current.cachedFilename=file.getName();current.finalized=true;current.pruned=false;store.index.save(current);
                if(!store.usable(current))throw new IOException("Audio unavailable");return file;
            }
        } finally {connection.disconnect();temporary.delete();}
    }
    static Uri uri(Context context,String id) { return new Uri.Builder().scheme("content").authority(context.getPackageName()+".recording-audio").appendPath(id).build(); }
}
