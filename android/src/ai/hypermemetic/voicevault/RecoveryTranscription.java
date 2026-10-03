package ai.hypermemetic.voicevault;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Identity lookup precedes any explicit recovery upload. No clipboard/input/UI dependency. */
final class RecoveryTranscription {
    static final class Result {
        final String text,state,serverId;
        Result(String text,String state,String serverId){this.text=text;this.state=state;this.serverId=serverId;}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(String body) throws IOException {
        Object value=RecordingIndex.Json.read(body);if(!(value instanceof Map))throw new DictationUpload.Failure("Invalid server response");return (Map<String,Object>)value;
    }
    static Result outcome(String body) throws IOException {
        Map<String,Object> data=object(body);
        if(!Boolean.TRUE.equals(data.get("ok")) || !(data.get("text") instanceof String))throw new DictationUpload.Failure("Invalid server response");
        String id=data.get("id") instanceof String ? (String)data.get("id") : "";
        if(!id.isEmpty() && !RecordingIndex.validId(id))throw new DictationUpload.Failure("Invalid server response");
        String text=(String)data.get("text");String state=Boolean.TRUE.equals(data.get("rejected")) ? "speaker_rejected" : text.trim().isEmpty() ? "no_speech" : "transcribed";
        return new Result(text,state,id);
    }
    static Result run(DictationUpload upload,String base,RecordingIndex.Entry entry,File local) throws IOException {
        String identity=entry.serverId.isEmpty() ? entry.id : entry.serverId;
        DictationUpload.Response lookup=upload.request(base+"/api/recording/"+identity,"GET",null,0,null,15000,30000);
        if(lookup.status==404 || lookup.status==405 || lookup.status==501) {
            if(local==null)throw new DictationUpload.Failure("Audio unavailable","audio_unavailable",0);
            return outcome(upload.post(base+"/api/transcribe",local,entry.durationMs,entry.id));
        }
        Map<String,Object> root=object(DictationUpload.accepted(lookup));Object item=root.get("recording");
        if(!Boolean.TRUE.equals(root.get("ok")) || !(item instanceof Map))throw new DictationUpload.Failure("Invalid server response");
        Map<?,?> recording=(Map<?,?>)item;
        if(!identity.equals(recording.get("id")))throw new DictationUpload.Failure("Invalid server response");
        Object status=recording.get("status"),completed=recording.get("outcome");
        if(completed instanceof Map)return outcome(RecordingIndex.Json.write(completed));
        if("transcribed".equals(status) || "speaker_rejected".equals(status) || "no_speech".equals(status)) {
            // Additive server migration may have a terminal legacy row without a stored envelope.
            java.util.LinkedHashMap<String,Object> result=new java.util.LinkedHashMap<>();result.put("ok",true);result.put("id",identity);
            result.put("text",recording.get("transcript"));result.put("rejected","speaker_rejected".equals(status));return outcome(RecordingIndex.Json.write(result));
        }
        if("processing".equals(status) || "intake".equals(status) || "pending".equals(status))throw new DictationUpload.Failure("Server is still processing — try lookup later","busy",409);
        if(Boolean.TRUE.equals(recording.get("audio_available"))) {
            DictationUpload.Response retry=upload.request(base+"/api/recording/"+identity+"/transcribe","POST",null,0,null,30000,600000);
            if(retry.status!=404 && retry.status!=405 && retry.status!=501)return outcome(DictationUpload.accepted(retry));
        }
        if(local==null)throw new DictationUpload.Failure("Audio unavailable","audio_unavailable",0);
        return outcome(upload.post(base+"/api/transcribe",local,entry.durationMs,entry.id));
    }
}
