package ai.hypermemetic.voicevault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, UUID-only provider. The manifest grants access only to an exact selected URI. */
public final class RecordingAudioProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File owned(Uri uri) throws FileNotFoundException {
        try {
            if(uri.getPathSegments().size()!=1 || !RecordingIndex.validId(uri.getLastPathSegment()))throw new FileNotFoundException();
            RecordingStore store=RecordingStore.get(getContext());RecordingIndex.Entry e=store.index.get(uri.getLastPathSegment());
            if(e==null || e.deleted || !store.usable(e))throw new FileNotFoundException();return store.audio(e);
        }catch(Exception error){throw new FileNotFoundException("Audio unavailable");}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(owned(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {
        try { String name=owned(uri).getName();if(name.endsWith(".wav"))return "audio/wav";if(name.endsWith(".webm"))return "audio/webm";if(name.endsWith(".ogg"))return "audio/ogg";if(name.endsWith(".mp3"))return "audio/mpeg";return "audio/mp4"; }
        catch(FileNotFoundException error){return "application/octet-stream";}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {
        try { File file=owned(uri);String[] cols=projection==null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(cols);Object[] values=new Object[cols.length];
            for(int i=0;i<cols.length;i++) {if(cols[i].equals(OpenableColumns.DISPLAY_NAME))values[i]="VoiceVault-recording"+file.getName().substring(file.getName().lastIndexOf('.'));if(cols[i].equals(OpenableColumns.SIZE))values[i]=file.length();}cursor.addRow(values);return cursor;
        }catch(Exception error){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
