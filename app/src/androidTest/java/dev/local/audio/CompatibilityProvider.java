package dev.local.audio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.Arrays;

/** Test APK only. Pure Java because its isolated provider process has no target Kotlin runtime. */
public final class CompatibilityProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.local.audio.test.compat";
    private ParcelFileDescriptor stalledWriter;
    @Override public boolean onCreate() { return true; }
    private String modeOf(Uri uri) {
        String mode = uri.getLastPathSegment();
        if (!Arrays.asList("known", "offset", "unknown", "pipe", "empty", "stall", "reliable-error", "pipe-offset", "pipe-truncated").contains(mode))
            throw new IllegalArgumentException("Synthetic fixture mode not found");
        return mode;
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192]; int count;
        while ((count=in.read(buffer)) != -1) out.write(buffer,0,count);
    }
    private synchronized File fixture(boolean wrapped) throws IOException {
        File file=new File(getContext().getCacheDir(),wrapped?"compat-wrapped.bin":"compat-plain.mp4");
        if(!file.exists()) {
            try(OutputStream out=new FileOutputStream(file); InputStream in=getContext().getAssets().open("compat/lc-h264.mp4")) {
                if(wrapped) {byte[] prefix=new byte[4096]; Arrays.fill(prefix,(byte)0x5a);out.write(prefix);}
                copy(in,out);
                if(wrapped) {byte[] suffix=new byte[2048]; Arrays.fill(suffix,(byte)0x33);out.write(suffix);}
            } catch(IOException error) {file.delete();throw error;}
        }
        return file;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read-only test provider");
        String selected=modeOf(uri);
        try {
            if("empty".equals(selected) || "stall".equals(selected)) {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
                if("empty".equals(selected)) pipe[1].close();
                else synchronized(this) {
                    if(stalledWriter!=null) stalledWriter.close();
                    stalledWriter=pipe[1];
                }
                return new AssetFileDescriptor(pipe[0],0,AssetFileDescriptor.UNKNOWN_LENGTH);
            }
            if("pipe".equals(selected) || "reliable-error".equals(selected) || "pipe-offset".equals(selected) || "pipe-truncated".equals(selected)) {
                final boolean pipeOffset="pipe-offset".equals(selected);
                final boolean truncated="pipe-truncated".equals(selected);
                final File payload=fixture(pipeOffset);
                final boolean reliableError="reliable-error".equals(selected);
                final ParcelFileDescriptor[] pipe=reliableError?ParcelFileDescriptor.createReliablePipe():ParcelFileDescriptor.createPipe();
                Thread writer=new Thread(new Runnable() {
                    @Override public void run() {
                        try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                            InputStream in=new FileInputStream(payload)) {
                            copy(in,out);
                            if(reliableError) pipe[1].closeWithError("Synthetic provider failure");
                        }
                        catch(IOException ignored) { /* Consumer may cancel or reject non-seekable descriptors. */ }
                    }
                },"synthetic-provider-writer");
                writer.setDaemon(true);writer.start();
                long start=pipeOffset?4096:0;
                long length=pipeOffset?payload.length()-4096-2048:(truncated?payload.length()+1000:AssetFileDescriptor.UNKNOWN_LENGTH);
                return new AssetFileDescriptor(pipe[0],start,length);
            }
            boolean wrapped="offset".equals(selected);
            File file=fixture(wrapped);
            ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
            long start=wrapped?4096L:0L;
            // UNKNOWN_LENGTH requires offset zero by AFD contract.
            long length="unknown".equals(selected)?AssetFileDescriptor.UNKNOWN_LENGTH:
                (wrapped?file.length()-4096L-2048L:file.length());
            return new AssetFileDescriptor(descriptor,start,length);
        } catch(IOException error) {
            FileNotFoundException failure=new FileNotFoundException("Synthetic fixture unavailable");
            failure.initCause(error);throw failure;
        }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        return openAssetFile(uri,mode).getParcelFileDescriptor();
    }
    @Override public String getType(Uri uri) {modeOf(uri);return "video/mp4";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {
        String selected=modeOf(uri);
        String[] columns=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) {
            if(OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]="synthetic-provider.mp4";
            else if(OpenableColumns.SIZE.equals(columns[i]) && !"unknown".equals(selected) && !"pipe".equals(selected) && !"empty".equals(selected) && !"stall".equals(selected) && !"reliable-error".equals(selected)) {
                try {row[i]=fixture(false).length();}catch(IOException error){throw new IllegalStateException("Synthetic fixture unavailable",error);}
            }
        }
        cursor.addRow(row);return cursor;
    }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras) {
        if(!"close-stall".equals(method)) throw new IllegalArgumentException("Unknown synthetic control");
        if(stalledWriter!=null) {
            try {stalledWriter.close();}catch(IOException ignored){}
            stalledWriter=null;
        }
        return Bundle.EMPTY;
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
}
