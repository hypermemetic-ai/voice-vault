package android.os;import java.io.*;
public class ParcelFileDescriptor {public static final int MODE_READ_ONLY=1;public final File file;private ParcelFileDescriptor(File file){this.file=file;}public static ParcelFileDescriptor open(File file,int mode)throws FileNotFoundException{if(!file.isFile() || mode!=MODE_READ_ONLY)throw new FileNotFoundException();return new ParcelFileDescriptor(file);}}
