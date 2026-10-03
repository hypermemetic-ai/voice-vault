package android.content;
import java.io.File;
public class Context {
 private final File root;
 public Context(File root){this.root=root;}
 public String getPackageName(){return "ai.hypermemetic.voicevault";}
 public Context getApplicationContext(){return this;}
 public File getFilesDir(){File d=new File(root,"private");d.mkdirs();return d;}
 public File getCacheDir(){File d=new File(root,"cache");d.mkdirs();return d;}
 public File getExternalFilesDir(String type){File d=new File(root,"external");d.mkdirs();return d;}
}
