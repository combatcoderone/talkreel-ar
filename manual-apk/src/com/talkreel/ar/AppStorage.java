package com.talkreel.ar;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** App-owned media paths that work with Android scoped storage. */
public final class AppStorage {
    private AppStorage() {}

    public static File videoDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "TalkReelAR");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File musicDir(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "TalkReelAR");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** New app-private path first, then legacy public folder for existing clips. */
    public static File findVideo(Context context, String name) {
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/")) return null;
        File current = new File(videoDir(context), name);
        if (current.isFile()) return current;
        File legacy = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TalkReelAR"), name);
        return legacy.isFile() ? legacy : current;
    }

    public static File[] listVideos(Context context) {
        List<File> result = new ArrayList<File>();
        File[] current = videoDir(context).listFiles();
        if (current != null) for (File f : current) if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".mp4")) result.add(f);
        File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TalkReelAR");
        File[] legacy = legacyDir.listFiles();
        if (legacy != null) for (File f : legacy) if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".mp4")) {
            boolean duplicate = false;
            for (File found : result) if (found.getName().equals(f.getName())) duplicate = true;
            if (!duplicate) result.add(f);
        }
        return result.toArray(new File[result.size()]);
    }
}