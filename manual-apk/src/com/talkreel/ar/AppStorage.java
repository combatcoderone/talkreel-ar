package com.talkreel.ar;

import android.content.Context;
import android.os.Environment;
import java.io.File;

/**
 * App-owned media directories. Public shared-storage paths cannot be written
 * directly by modern target-SDK apps under scoped storage. Keeping media in
 * the app-specific external directories avoids broad storage permissions.
 * Clips remain shareable through ClipProvider and are removed on uninstall.
 */
public final class AppStorage {
    private AppStorage() {}

    public static File videoDir(Context context) {
        return childDir(context, Environment.DIRECTORY_MOVIES, "TalkReelAR");
    }

    public static File musicDir(Context context) {
        return childDir(context, Environment.DIRECTORY_MUSIC, "TalkReelAR");
    }

    private static File childDir(Context context, String type, String name) {
        File parent = context.getExternalFilesDir(type);
        if (parent == null) parent = new File(context.getFilesDir(), type);
        File dir = new File(parent, name);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("Cannot create app media directory: " + dir);
        }
        return dir;
    }
}
