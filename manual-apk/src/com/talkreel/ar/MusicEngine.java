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

    public static File musicDir(Context context) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "TalkReelAR");
        dir.mkdirs();
        return dir;
    }

    public static File localFile(Context context, MusicCatalog.Track track) {
        return new File(musicDir(context), track.fileName);
    }

    public static boolean isDownloaded(Context context, MusicCatalog.Track track) {
        File f = localFile(context, track);
        return f.exists() && f.length() > 10000;
    }

    public static boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public static String playingTitle() { return playingTitle; }

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
            File local = localFile(context, track);
            if (local.exists() && local.length() > 10000) {
                player.setDataSource(local.getAbsolutePath());
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

    // Download a track locally, on a background thread.
    public static void download(final Context context, final MusicCatalog.Track track, final Handler mainHandler, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                File out = localFile(context, track);
                try {
                    URL url = new URL(track.downloadUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    if (conn.getResponseCode() >= 400) throw new Exception("HTTP " + conn.getResponseCode());
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close(); in.close(); conn.disconnect();
                    if (out.length() < 10000) throw new Exception("Download too small, try again");
                    mainHandler.post(new Runnable() { public void run() { cb.onResult(true, "Downloaded: " + track.title + " to Music/TalkReelAR"); } });
                } catch (final Exception e) {
                    try { if (out.exists()) out.delete(); } catch (Exception ignored) {}
                    mainHandler.post(new Runnable() { public void run() { cb.onResult(false, "Download failed: " + e.getMessage()); } });
                }
            }
        }).start();
    }
}
