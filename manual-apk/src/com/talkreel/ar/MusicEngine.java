package com.talkreel.ar;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Environment;
import android.os.Handler;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// MusicEngine, v0.7.0. Real music, not synthesized beeps.
// Play for test: if the track is downloaded, play the local file.
// If it is not downloaded yet, stream the download URL, so the user can
// hear it before deciding to download. Download locally: save the MP3
// in Music/TalkReelAR, on a background thread, never on the UI thread.
// Use in clip: play the selected track on loop while recording, stop it
// when recording stops. The music plays through the phone speaker, so the
// mic hears it. That is the honest limit: it is background music in the
// room, not mixed into the video file by an encoder. Mixing it into the
// file needs a video encoder step, planned after v0.7.
public class MusicEngine {
    public interface Callback { void onResult(boolean ok, String message); }

    private static MediaPlayer player = null;
    private static String playingTitle = null;
    private static float volume = 0.80f;

    public static File musicDir(Context context) {
        return AppStorage.musicDir(context);
    }

    /** New downloads are stored in app-specific music storage (no broad storage permission). */
    public static File localFile(Context context, MusicCatalog.Track track) {
        return new File(musicDir(context), track.fileName);
    }

    /** Read older downloads from the former public Music/TalkReelAR folder when present. */
    private static File legacyFile(MusicCatalog.Track track) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "TalkReelAR");
        return new File(dir, track.fileName);
    }

    public static boolean isDownloaded(Context context, MusicCatalog.Track track) {
        File current = localFile(context, track);
        File legacy = legacyFile(track);
        return (current.exists() && current.length() > 10000) || (legacy.exists() && legacy.length() > 10000);
    }

    public static boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public static String playingTitle() { return playingTitle; }

    public static void setVolume(float value) {
        volume = Math.max(0.0f, Math.min(1.0f, value));
        try { if (player != null) player.setVolume(volume, volume); } catch (Exception ignored) {}
    }

    public static void stop() {
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception e) {}
        player = null;
        playingTitle = null;
    }

    // Play a track. Loop only when it is used in a clip, otherwise play once.
    public static void play(final Context context, final MusicCatalog.Track track, final boolean loop, final Handler mainHandler, final Callback cb) {
        stop();
        try {
            player = new MediaPlayer();
            player.setLooping(loop);
            player.setVolume(volume, volume);
            File local = localFile(context, track);
            File legacy = legacyFile(track);
            if (local.exists() && local.length() > 10000) {
                player.setDataSource(local.getAbsolutePath());
            } else if (legacy.exists() && legacy.length() > 10000) {
                player.setDataSource(legacy.getAbsolutePath());
            } else {
                player.setDataSource(track.downloadUrl);
            }
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    try { mp.start(); playingTitle = track.title; } catch (Exception e) {}
                    if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(true, "Playing: " + track.title); } });
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Play failed. Check internet, then try Download first."); } });
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception e) {
            if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Play failed: " + e.getMessage()); } });
        }
    }

    // Play a user-selected audio document. The Storage Access Framework URI
    // permission is persisted by MainActivity before this method is called.
    public static void playUri(final Context context, final android.net.Uri uri, final boolean loop, final Handler mainHandler, final Callback cb) {
        stop();
        if (uri == null) {
            if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "No audio file selected."); } });
            return;
        }
        try {
            player = new MediaPlayer();
            player.setLooping(loop);
            player.setVolume(volume, volume);
            player.setDataSource(context, uri);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    try { mp.start(); playingTitle = "Your audio"; } catch (Exception ignored) {}
                    if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(true, "Playing your selected audio."); } });
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Could not play this audio file. Choose an MP3, M4A or WAV file."); } });
                    return true;
                }
            });
            player.prepareAsync();
        } catch (final Exception e) {
            if (cb != null) mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Could not open audio: " + e.getMessage()); } });
        }
    }

    private static boolean looksLikeMp3(File file) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            int a = in.read();
            int b = in.read();
            int d = in.read();
            return a == 'I' && b == 'D' && d == '3'
                || a == 0xFF && b >= 0 && (b & 0xE0) == 0xE0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    // Download a track locally, on a background thread.
    public static void download(final Context context, final MusicCatalog.Track track, final Handler mainHandler, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                File out = localFile(context, track);
                File temp = new File(out.getParentFile(), out.getName() + ".part");
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(track.downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "TalkReelAR/0.11 Android");
                    conn.connect();
                    if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) throw new Exception("HTTP " + conn.getResponseCode());
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(temp);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.getFD().sync();
                    fos.close();
                    in.close();
                    if (temp.length() < 10000 || !looksLikeMp3(temp)) throw new Exception("The server did not return a valid MP3. Try again later.");
                    File backup = new File(out.getParentFile(), out.getName() + ".bak");
                    if (backup.exists()) backup.delete();
                    boolean backedUp = out.exists() && out.renameTo(backup);
                    if (out.exists() && !backedUp) throw new Exception("Could not protect the previous track file.");
                    if (!temp.renameTo(out)) {
                        if (backedUp) backup.renameTo(out);
                        throw new Exception("Could not save the downloaded track.");
                    }
                    if (backup.exists()) backup.delete();
                    mainHandler.post(new Runnable() { public void run() { cb.onResult(true, "Downloaded: " + track.title + " to TalkReel AR app storage"); } });
                } catch (final Exception e) {
                    try { if (temp.exists()) temp.delete(); } catch (Exception ignored) {}
                    mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Download failed: " + e.getMessage()); } });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }
}
