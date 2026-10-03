package android.util;
import java.io.*;
import java.nio.file.*;
/** Deterministic filesystem adapter with injectable write faults, matching the AtomicFile API. */
public class AtomicFile {
 public static String failName="";
 private final File base, backup;
 public AtomicFile(File base){this.base=base;backup=new File(base+".bak");}
 public File getBaseFile(){return base;}
 public byte[] readFully()throws IOException {if(backup.exists()){Files.move(backup.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);}return Files.readAllBytes(base.toPath());}
 public FileOutputStream startWrite()throws IOException {
  if(base.getName().equals(failName))throw new IOException("synthetic storage fault");
  if(base.exists())Files.move(base.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING);
  return new FileOutputStream(base);
 }
 public void finishWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}backup.delete();}
 public void failWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}base.delete();try{if(backup.exists())Files.move(backup.toPath(),base.toPath());}catch(IOException ignored){}}
}
