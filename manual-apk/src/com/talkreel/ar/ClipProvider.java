package com.talkreel.ar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

// Shares a recorded clip safely.
// v0.2 used Uri.fromFile, which Android blocks on Android 7 and newer
// with FileUriExposedException, so Share crashed or did nothing.
// This provider serves the same file as a content:// URI, with a
// temporary read permission for the app that receives it.
public class ClipProvider extends ContentProvider {
    public static final String AUTHORITY = "com.talkreel.ar.clips";

    public static Uri uriForFile(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    private File fileForUri(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) return null;
        return AppStorage.findVideo(getContext(), name);
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileForUri(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException("Clip not found: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) { return "video/mp4"; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileForUri(uri);
        MatrixCursor c = new MatrixCursor(new String[]{"_display_name", "_size"});
        if (f != null && f.exists()) c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
