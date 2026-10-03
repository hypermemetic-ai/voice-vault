package ai.hypermemetic.voicevault;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public final class RecoveryHarness {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static Context context;static Path root;
 static RecordingStore fresh()throws Exception {java.lang.reflect.Field f=RecordingStore.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);return RecordingStore.get(context);}
 static File audio(int n,String body)throws Exception {File file=new File(context.getExternalFilesDir("recordings"),"dictation_"+(1700000000000L+n)+".m4a");Files.writeString(file.toPath(),body);return file;}
 static RecordingIndex.Entry capture(RecordingStore store,int n)throws Exception {File file=audio(n,"synthetic-valid-audio");return store.capture(file,1700000000000L+n);}
 static void state()throws Exception {
  RecordingStore s=fresh();RecordingIndex.Entry e=capture(s,1);check(!e.finalized,"intent not finalized");
  s.finalizeCapture(e.id,123);RecordingIndex.Entry attempt=s.index.begin(e.id);
  check(attempt!=null && s.index.begin(e.id)==null,"one durable owner");
  check(s.index.finish(e.id,attempt.attempt,"failed","server_error","",""),"failure committed");
  s.index.interrupt(e.id,"interrupted");check(s.index.get(e.id).failure.equals("server_error"),"destroy preserves terminal failure");
  RecordingIndex.Entry retry=s.index.begin(e.id);s.index.interrupt(e.id,"canceled");
  check(!s.index.finish(e.id,retry.attempt,"transcribed","","late",""),"late canceled result ignored");
  retry=s.index.begin(e.id);check(s.index.finish(e.id,retry.attempt,"transcribed","","quoted \"text\"\nline","server-1"),"completion");
  check(s.index.get(e.id).failure.isEmpty(),"no false error on success");
  long time=e.capturedAt;s=fresh();check(s.index.get(e.id).capturedAt==time && s.index.get(e.id).transcript.contains("\n"),"JSON/capture persistence");
 }
 static void storage()throws Exception {
  RecordingStore s=fresh();RecordingIndex.Entry e=capture(s,1);AtomicFile.failName="recording-index.json";
  try{s.finalizeCapture(e.id,123);throw new AssertionError("expected write fault");}catch(IOException expected){}
  check(!s.index.get(e.id).finalized && s.index.get(e.id).state.equals("recording"),"failed write cannot publish saved state");
  AtomicFile.failName="";s=fresh();check(s.index.get(e.id).finalized && s.index.get(e.id).state.equals("interrupted"),"valid audio repaired after finalize crash");
  RecordingIndex.Entry processing=s.index.begin(e.id);s=fresh();check(s.index.get(e.id).state.equals("interrupted"),"restart interrupts processing without retry");
  RecordingIndex.Entry owned=s.index.begin(e.id);AtomicFile.failName="recording-index.json";
  try{s.index.finish(e.id,owned.attempt,"transcribed","","output","");throw new AssertionError();}catch(IOException expected){}
  AtomicFile.failName="";VoiceVaultService.processing=true;s.reconcile(null);check(s.index.get(e.id).state.equals("processing"),"live service owner protected");
  VoiceVaultService.processing=false;s.reconcile(null);check(s.index.get(e.id).state.equals("interrupted"),"restored storage reconciles ownerless singleton");
  owned=s.index.begin(e.id);check(owned!=null,"manual retry no process restart needed");s.index.finish(e.id,owned.attempt,"failed","storage_full","","");
  Files.writeString(new File(context.getFilesDir(),"recording-index.json").toPath(),"{broken");
  s=fresh();check(s.index.get(e.id)!=null,"prior valid index recovered");
  File invalid=audio(9,"bad");RecordingIndex.Entry bad=s.capture(invalid,1700000000009L);s=fresh();check(!s.index.get(bad.id).finalized,"invalid capture remains unavailable");
 }
 static void discovery()throws Exception {
  RecordingStore s=fresh();File old=audio(1,"synthetic-valid-audio");File empty=audio(2,"");audio(3,"invalid");
  Files.writeString(new File(context.getFilesDir(),"dictation_1700000000004.mp4").toPath(),"synthetic-valid-audio");
  Files.writeString(new File(context.getFilesDir(),"voice_sample.m4a").toPath(),"synthetic-valid-audio");
  Files.createSymbolicLink(new File(context.getExternalFilesDir("recordings"),"dictation_1700000000005.m4a").toPath(),old.toPath());
  s.reconcile(null);check(s.index.all().size()==4,"both locations, invalid visible, unrelated/symlink excluded");
  s.reconcile(null);check(s.index.all().size()==4,"repeat idempotence");
  RecordingIndex.Entry first=s.index.all().get(0);check(first.state.equals("recovered"),"unknown outcome honest");
  check(s.index.all().stream().filter(e->sUsable(e)).count()==2,"only valid originals usable");
  RecordingIndex.Entry active=capture(s,8);s.reconcile(active.id);check(!s.index.get(active.id).finalized && s.index.get(active.id).state.equals("recording"),"active capture never adopted");
  s.index.restart();s.reconcile(active.id);check(!s.index.get(active.id).finalized,"active ID excludes interrupted capture");
  s.reconcile(null);check(s.index.get(active.id).finalized,"ownerless valid capture recovered");
  for(int i=10;i<100;i++) {RecordingIndex.Entry e=capture(s,i);s.finalizeCapture(e.id,1000);RecordingIndex.Entry a=s.index.begin(e.id);s.index.finish(e.id,a.attempt,i<70 ? "failed" : "transcribed",i<70 ? "server_error" : "",i<70 ? "" : "text","");}
  s.prune();long unresolved=s.index.all().stream().filter(e->!e.resolved() && sUsable(e)).count();
  long resolved=s.index.all().stream().filter(e->e.resolved() && sUsable(e)).count();
  check(unresolved>=60 && resolved==20,"unresolved beyond audio limit protected");
  int count=s.index.all().size();s.reconcile(null);check(s.index.all().size()==count,"pruned resolved not rediscovered");
 }
 static boolean sUsable(RecordingIndex.Entry e){try{return RecordingStore.get(context).usable(e);}catch(Exception error){throw new RuntimeException(error);}}
 static void history()throws Exception {
  RecordingStore s=fresh();List<HistoryManager.Entry> legacy=new ArrayList<>();legacy.add(new HistoryManager.Entry("local_old",1,5,"old text"));
  for(int i=1;i<65;i++) {RecordingIndex.Entry e=capture(s,i);s.finalizeCapture(e.id,1000);RecordingIndex.Entry a=s.index.begin(e.id);s.index.finish(e.id,a.attempt,"failed","server_error","","");}
  RecordingIndex.Entry first=s.index.all().get(0);HistoryManager.Entry remote=new HistoryManager.Entry(first.id,first.capturedAt+100000,1000,"retried text");remote.status="transcribed";remote.audioAvailable=true;
  List<HistoryManager.Entry> merged=HistoryManager.merge(context,legacy,List.of(remote));
  check(merged.size()==65,"limited remote page preserves local failures/legacy");
  check(merged.stream().filter(e->e.id.equals(first.id)).count()==1,"same UUID merges one entry");
  HistoryManager.Entry stale=new HistoryManager.Entry(first.id,first.capturedAt,1,"");stale.status="failed";
  HistoryManager.merge(context,legacy,List.of(stale));check(s.index.get(first.id).state.equals("transcribed"),"stale remote failure cannot downgrade local completion");
  check(merged.stream().filter(e->e.id.equals(first.id)).findFirst().get().timestamp==first.capturedAt,"capture time survives retry");
  check(HistoryManager.latestEntry(merged).transcript.equals("retried text"),"newest nonblank preview");
  for(int i=100;i<160;i++)s.remote("remote-"+i,1700000000000L+i,1,"transcribed","","remote text",false);
  merged=HistoryManager.merge(context,legacy,new ArrayList<>());
  check(merged.stream().filter(e->e.status.equals("failed")).count()==63,"transcript display limit leaves failures visible");
  check(merged.stream().filter(e->e.status.equals("transcribed")).count()==50,"resolved transcript limit");
 }
 static void deletion()throws Exception {
  RecordingStore s=fresh();RecordingIndex.Entry e=capture(s,1),other=capture(s,2);s.finalizeCapture(e.id,1);s.finalizeCapture(other.id,1);
  s.index.begin(e.id);check(s.deleteLocal(e.id)==null,"cannot delete owned processing");s.index.interrupt(e.id,"canceled");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
  try {
   VoiceVaultApi.BASE_URL="http://127.0.0.1:"+server.getAddress().getPort();
   HistoryManager.Entry item=new HistoryManager.Entry(e.id,e.capturedAt,1,"");item.localId=e.id;item.status="interrupted";
   check(!HistoryManager.delete(context,item),"remote outage reports partial cleanup");
   check(s.index.get(e.id).deleted && s.index.get(e.id).cleanupPending,"durable suppression and cleanup action");
   check(s.audio(other)!=null && !new File(context.getExternalFilesDir("recordings"),e.filename).exists(),"only selected original removed");
   HistoryManager.Entry stale=new HistoryManager.Entry(e.id,e.capturedAt,1,"stale");stale.status="transcribed";
   List<HistoryManager.Entry> merged=HistoryManager.merge(context,List.of(stale),List.of(stale));
   check(merged.stream().filter(x->x.id.equals(e.id)).count()==1 && merged.stream().filter(x->x.id.equals(e.id)).findFirst().get().cleanupPending,"stale refresh cannot resurrect deletion");
  }finally{server.stop(0);}
 }
 static void network()throws Exception {
  RecordingStore s=fresh();RecordingIndex.Entry e=capture(s,1);s.finalizeCapture(e.id,1000);e=s.index.get(e.id);final String id=e.id;
  File original=s.audio(e);List<String> requests=new ArrayList<>();int[] mode={0},uploads={0},retries={0};
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   String route=exchange.getRequestURI().getPath();requests.add(exchange.getRequestMethod()+" "+route);
   String body="";int status=200;
   if(route.equals("/api/transcribe")) {uploads[0]++;check(id.equals(exchange.getRequestHeaders().getFirst("X-Recording-Id")),"upload stable identity");exchange.getRequestBody().readAllBytes();body="{\"ok\":true,\"id\":\""+id+"\",\"text\":\"uploaded\",\"gate\":{\"score\":0.75}}";
     if(mode[0]==3){mode[0]=0;exchange.close();return;}}
   else if(route.endsWith("/transcribe")){retries[0]++;body="{\"ok\":true,\"id\":\""+id+"\",\"text\":\"retried\"}";}
   else if(mode[0]==1){status=404;body="Not found";}
   else if(mode[0]==2){body="{\"ok\":true,\"recording\":{\"id\":\""+id+"\",\"status\":\"failed\",\"audio_available\":true}}";}
   else if(mode[0]==4){status=500;body="{\"errorCategory\":\"storage_full\",\"error\":\"/private/path secret body\"}";}
   else if(mode[0]==5){body="{\"ok\":true,\"recording\":{\"id\":\""+id+"\",\"status\":\"processing\"}}";}
   else {body="{\"ok\":true,\"recording\":{\"id\":\""+id+"\",\"status\":\"transcribed\",\"outcome\":{\"ok\":true,\"id\":\""+id+"\",\"text\":\"stored\",\"gate\":{\"score\":0.75}}}}";}
   byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
  try {
   check(RecoveryTranscription.run(new DictationUpload(),base,e,original).text.equals("stored") && uploads[0]==0,"completed lookup avoids upload");
   mode[0]=2;check(RecoveryTranscription.run(new DictationUpload(),base,e,original).text.equals("retried") && retries[0]==1,"explicit server retry");
   mode[0]=1;check(RecoveryTranscription.run(new DictationUpload(),base,e,original).text.equals("uploaded") && uploads[0]==1,"legacy endpoint fallback");
   mode[0]=3;try{new DictationUpload().post(base+"/api/transcribe",original,1000,id);throw new AssertionError("response loss expected");}catch(IOException expected){}
   check(RecoveryTranscription.run(new DictationUpload(),base,e,original).text.equals("stored") && uploads[0]==2,"response loss recovered without second upload");
   mode[0]=4;try{RecoveryTranscription.run(new DictationUpload(),base,e,original);throw new AssertionError();}catch(DictationUpload.Failure failure){check(failure.category.equals("storage_full") && !failure.getMessage().contains("private"),"safe failure feedback");}
   mode[0]=5;try{RecoveryTranscription.run(new DictationUpload(),base,e,original);throw new AssertionError();}catch(DictationUpload.Failure failure){check(failure.category.equals("busy"),"live pending stays manually visible");}
   check(original.exists() && Files.readString(original.toPath()).equals("synthetic-valid-audio"),"all recovery keeps original");
   check(RecoveryTranscription.outcome("{\"ok\":true,\"text\":\"\",\"rejected\":true}").state.equals("speaker_rejected"),"valid speaker rejection");
   check(RecoveryTranscription.outcome("{\"ok\":true,\"text\":\"\"}").state.equals("no_speech"),"valid no speech");
  }finally{server.stop(0);}
 }
 static void export()throws Exception {
  RecordingStore s=fresh();RecordingIndex.Entry e=capture(s,1);s.finalizeCapture(e.id,1);
  RecordingAudioProvider provider=new RecordingAudioProvider();provider.testContext(context);
  android.net.Uri uri=RecordingAudio.uri(context,e.id);
  check(uri.toString().equals("content://ai.hypermemetic.voicevault.recording-audio/"+e.id),"exact identity URI, no file path");
  check(provider.openFile(uri,"r").file.equals(s.audio(s.index.get(e.id))),"only owned selected audio");
  try{provider.openFile(uri,"w");throw new AssertionError();}catch(FileNotFoundException expected){}
  for(String value:new String[]{"../outside","unknown",e.id+"/other"})try{provider.openFile(new android.net.Uri("content://ai.hypermemetic.voicevault.recording-audio/"+value),"r");throw new AssertionError();}catch(FileNotFoundException expected){}
  check(RecordingAudio.prepare(context,e.id).equals(s.audio(s.index.get(e.id))),"local playback/export works without server");
  s.index.serverCopy(e.id,0,e.id,true); // Not processing: ownership guard intentionally ignores this.
  RecordingIndex.Entry entry=s.index.get(e.id);entry.serverId=e.id;entry.serverAudio=true;s.index.save(entry);
  Files.writeString(new File(context.getExternalFilesDir("recordings"),entry.filename).toPath(),"invalid local copy");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{byte[] bytes="synthetic-valid-audio remote".getBytes();exchange.getResponseHeaders().set("Content-Type","audio/mp4");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
  File retrieved;
  try{VoiceVaultApi.BASE_URL="http://127.0.0.1:"+server.getAddress().getPort();retrieved=RecordingAudio.prepare(context,e.id);check(retrieved.exists() && !s.index.get(e.id).cachedFilename.isEmpty(),"known server copy streamed into narrowly owned cache");}
  finally{server.stop(0);}
  s.index.interrupt(e.id,"canceled");s.deleteLocal(e.id);check(!retrieved.exists() && !new File(context.getExternalFilesDir("recordings"),entry.filename).exists(),"deletion removes both associated local and fetched copies");try{provider.openFile(uri,"r");throw new AssertionError();}catch(FileNotFoundException expected){}
 }
 public static void main(String[] args)throws Exception {
  root=Files.createTempDirectory("vv-recovery-java-");context=new Context(root.toFile());
  try{switch(args[0]){case "state":state();break;case "storage":storage();break;case "discovery":discovery();break;case "history":history();break;case "deletion":deletion();break;case "network":network();break;case "export":export();break;default:throw new AssertionError();}}
  finally {try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
 }
}
