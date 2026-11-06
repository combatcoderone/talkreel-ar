package com.talkreel.ar;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Publishes a completed app-private recording into the user's Movies collection. */
public final class MediaLibrary {
    private MediaLibrary() {}

    public static Uri publishVideo(ContentResolver resolver, File source) {
        if (Build.VERSION.SDK_INT < 29 || source == null || !source.isFile() || source.length() < 4096) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TalkReelAR");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            OutputStream out = resolver.openOutputStream(uri, "w");
            if (out == null) throw new IllegalStateException("MediaStore output stream unavailable");
            long copied = 0;
            try (FileInputStream in = new FileInputStream(source); OutputStream target = out) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    target.write(buffer, 0, count);
                    copied += count;
                }
                target.flush();
            }
            if (copied != source.length()) throw new IllegalStateException("Gallery copy was incomplete");
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        } catch (Exception e) {
            if (uri != null) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            return null;
        }
    }
}