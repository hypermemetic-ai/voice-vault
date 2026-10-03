package android.media;
import java.nio.file.*;
public class MediaMetadataRetriever {
 public static final int METADATA_KEY_DURATION=9;
 private String path;
 public void setDataSource(String path){this.path=path;}
 public String extractMetadata(int key){try{String text=Files.readString(Path.of(path));return text.startsWith("synthetic-valid-audio") ? "1000" : null;}catch(Exception e){return null;}}
 public void release(){}
}
