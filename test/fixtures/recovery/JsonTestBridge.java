package ai.hypermemetic.voicevault;
public class JsonTestBridge {
 public static Object read(String text){try{return RecordingIndex.Json.read(text);}catch(Exception e){throw new IllegalArgumentException();}}
 public static String write(Object value){return RecordingIndex.Json.write(value);}
}
