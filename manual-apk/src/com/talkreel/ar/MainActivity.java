package com.talkreel.ar;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// v0.6 professional rebuild.
// Single owner for recording: this activity only.
// Camera: one open at a time, guarded by cameraOpening.
// Overlay: never shown automatically on Record. Only when
// the user taps Overlay. Overlay buttons only send a request,
// they never change state here.
public class MainActivity extends Activity {
    private TextureView textureView;
    private ProFrameView frameView;
    private TextView statusView;
    private TextView coachView;
    private TextView audioView;
    private TextView captionView;
    private SafeScrollView teleprompterScroll;
    private TextView teleprompterView;
    private EditText scriptEdit;
    private Button recordButton;
    private Button pauseButton;
    private Button stopButton;
    private Button proButton;
    private Button langButton;
    private Button textColorButton;
    private Button captionColorButton;
    private Button musicToggleButton;
    private TextView musicStatusView;
    private int scriptEditorHeight = 260;
    private boolean preferFrontCamera = true;
    private int musicTrackIndex = 0;
    private boolean useMusic = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private MediaRecorder mediaRecorder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private boolean speechOn = false;
    private String currentCaptionLang = "";
    private long lastCaptionAt = 0;
    private long lastLangSwitchAt = 0;
    private int wordsInWindow = 0;
    private long windowStartAt = 0;
    private int restartCount = 0;
    private volatile boolean cameraOpening = false;
    private volatile boolean cameraReleasedForSocial = false;
    private int quietSeconds = 0;

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (AppState.ACTION_RECORD.equals(a)) {
                if (!AppState.isRecording) startRecording(); else stopRecording();
            } else if (AppState.ACTION_PAUSE.equals(a)) {
                togglePause();
            } else if (AppState.ACTION_STOP.equals(a)) {
                stopRecording();
            }
        }
    };

    private Runnable ticker = new Runnable() {
        public void run() {
            if (AppState.isRecording && !AppState.isPaused) {
                AppState.seconds += 1;
                float effSpeed = AppState.speed;
                if (System.currentTimeMillis() - windowStartAt > 10000) {
                    effSpeed = AppState.speed * AiEngine.speedFromWords(wordsInWindow);
                    wordsInWindow = 0;
                    windowStartAt = System.currentTimeMillis();
                }
                teleprompterScroll.scrollBy(0, (int) (6 * effSpeed));
                int amp = 0;
                boolean quiet = true;
                if (mediaRecorder != null) {
                    try {
                        amp = mediaRecorder.getMaxAmplitude();
                        boolean heard = System.currentTimeMillis() - lastCaptionAt < 3000;
                        quiet = amp < 200 && !heard;
                    } catch (Exception e) {}
                }
                if (quiet) quietSeconds += 1; else quietSeconds = 0;
                SilenceLog.tick(AppState.seconds, quietSeconds >= 3);
                String bars = amp < 200 ? "▁" : amp < 1000 ? "▂▄" : amp < 5000 ? "▂▄▆" : "▂▄▆█";
                String light = AppState.faceFound ? "Light good" : "Face not in frame";
                audioView.setText("Mic " + bars + "  " + light);
                statusView.setText("REC " + AppState.seconds + "s  " + AppState.exportLabel());
                mainHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        AppState.script = prefs.getString("script", AppState.script);
        AppState.speed = prefs.getFloat("speed", 1.0f);
        AppState.langMode = prefs.getInt("langMode", 0);
        AppState.showFrame = prefs.getBoolean("showFrame", true);
        AppState.arFilter = prefs.getInt("arFilter", ProFrameView.FILTER_GUIDE);
        AppState.isPro = prefs.getBoolean("isPro", false);
        AppState.textColorIndex = prefs.getInt("textColor", 0);
        AppState.captionColorIndex = prefs.getInt("captionColor", 1);
        musicTrackIndex = prefs.getInt("musicTrack", 0);
        scriptEditorHeight = prefs.getInt("scriptEditorHeight", 260);
        preferFrontCamera = prefs.getBoolean("faceOn", true);
        useMusic = prefs.getBoolean("useMusic", false);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        IntentFilter f = new IntentFilter();
        f.addAction(AppState.ACTION_RECORD);
        f.addAction(AppState.ACTION_PAUSE);
        f.addAction(AppState.ACTION_STOP);
        // v0.6.1 fix, the blink / crash on launch on Android 14:
        // Android 14 requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        // when registering a receiver. The old 2-arg call throws
        // SecurityException on Android 14, so the app opened and
        // closed at once. This receiver is only for our own overlay,
        // so it must NOT be exported.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, f);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(16, 16, 16, 16);

        statusView = new TextView(this);
        statusView.setText(AppState.isPro ? "Ready  Pro  " + AppState.exportLabel() : "Ready  Free  " + AppState.exportLabel());
        statusView.setTextColor(0xFFAAAAAA);
        statusView.setTextSize(13);
        statusView.setPadding(8, 4, 8, 8);
        root.addView(statusView);

        coachView = new TextView(this);
        coachView.setText(ScriptCoach.shortLine(AppState.script));
        coachView.setTextColor(0xFF88CCFF);
        coachView.setTextSize(13);
        coachView.setPadding(8, 4, 8, 8);
        root.addView(coachView);

        audioView = new TextView(this);
        audioView.setText("Mic ▁");
        audioView.setTextColor(0xFFAAAAAA);
        audioView.setTextSize(13);
        audioView.setPadding(8, 4, 8, 8);
        root.addView(audioView);

        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 520));
        textureView = new TextureView(this);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewBox.addView(textureView);
        frameView = new ProFrameView(this);
        frameView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frameView.setFilter(AppState.arFilter);
        previewBox.addView(frameView);
        root.addView(previewBox);
        TextView arNote = new TextView(this);
        arNote.setText("Frame FX are graphic overlays. Open AR Studio for ARCore 3D face tracking.");
        arNote.setTextColor(0xFFAAAAAA);
        arNote.setTextSize(11);
        arNote.setPadding(8, 3, 8, 5);
        root.addView(arNote);

        captionView = new TextView(this);
        captionView.setText("");
        captionView.setTextColor(AppState.captionColor());
        captionView.setTextSize(18);
        captionView.setPadding(8, 8, 8, 8);
        root.addView(captionView);

        teleprompterScroll = new SafeScrollView(this);
        teleprompterScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 380));
        teleprompterScroll.setBackground(UiStyle.panelBg());
        teleprompterScroll.setPadding(16, 16, 16, 16);
        teleprompterScroll.setVerticalScrollBarEnabled(false);
        teleprompterView = new TextView(this);
        teleprompterView.setText(AppState.script);
        teleprompterView.setTextColor(AppState.textColor());
        teleprompterView.setTextSize(26);
        teleprompterView.setLineSpacing(8, 1.35f);
        teleprompterView.setPadding(8, 8, 8, 8);
        teleprompterScroll.addView(teleprompterView);
        root.addView(teleprompterScroll);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = UiStyle.primary(this, "Record");
        recordButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (!AppState.isRecording) startRecording(); else stopRecording(); }
        });
        row1.addView(recordButton);
        pauseButton = UiStyle.dark(this, "Pause");
        pauseButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePause(); }
        });
        row1.addView(pauseButton);
        stopButton = UiStyle.dark(this, "Stop");
        stopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopRecording(); }
        });
        row1.addView(stopButton);
        root.addView(row1);

        LinearLayout rowPro = new LinearLayout(this);
        rowPro.setOrientation(LinearLayout.HORIZONTAL);
        proButton = UiStyle.primary(this, AppState.isPro ? "Pro: On" : "Pro: Off");
        proButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.isPro = !AppState.isPro;
                proButton.setText(AppState.isPro ? "Pro: On" : "Pro: Off");
                savePrefs();
                coachView.setText(ScriptCoach.shortLine(scriptEdit.getText().toString()));
                statusView.setText(AppState.isPro ? "Pro on  " + AppState.exportLabel() : "Free  " + AppState.exportLabel());
            }
        });
        rowPro.addView(proButton);
        Button coachButton = UiStyle.dark(this, "Coach");
        coachButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.script = scriptEdit.getText().toString();
                coachView.setText(ScriptCoach.shortLine(AppState.script));
                savePrefs();
            }
        });
        rowPro.addView(coachButton);
        final Button frameButton = UiStyle.dark(this, AppState.showFrame ? "Frame: On" : "Frame: Off");
        frameButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.showFrame = !AppState.showFrame;
                frameButton.setText(AppState.showFrame ? "Frame: On" : "Frame: Off");
                savePrefs();
                if (frameView != null) frameView.postInvalidate();
            }
        });
        rowPro.addView(frameButton);
        final Button faceButton = UiStyle.dark(this, preferFrontCamera ? "Face: On" : "Face: Off");
        faceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AppState.isRecording) {
                    Toast.makeText(MainActivity.this, "Stop the clip before changing Face mode.", Toast.LENGTH_SHORT).show();
                    return;
                }
                preferFrontCamera = !preferFrontCamera;
                faceButton.setText(preferFrontCamera ? "Face: On" : "Face: Off");
                savePrefs();
                releaseCamera();
                statusView.setText(preferFrontCamera ? "Face mode on: front camera" : "Face mode off: rear camera");
                mainHandler.postDelayed(new Runnable() { public void run() { openCamera(); } }, 350);
            }
        });
        rowPro.addView(faceButton);
        final Button arFilterButton = UiStyle.dark(this, "Frame FX: " + ProFrameView.filterName(AppState.arFilter));
        arFilterButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.arFilter = (AppState.arFilter + 1) % ProFrameView.FILTER_COUNT;
                frameView.setFilter(AppState.arFilter);
                arFilterButton.setText("Frame FX: " + ProFrameView.filterName(AppState.arFilter));
                savePrefs();
                statusView.setText("AR preview: " + ProFrameView.filterName(AppState.arFilter) + " · Guide turns effects off");
            }
        });
        rowPro.addView(arFilterButton);
        root.addView(rowPro);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button overlayButton = UiStyle.primary(this, "Overlay");
        overlayButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startOverlay(); }
        });
        row2.addView(overlayButton);
        Button instaButton = UiStyle.dark(this, "Instagram");
        instaButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSocialApp("com.instagram.android", "Instagram"); }
        });
        row2.addView(instaButton);
        Button ytButton = UiStyle.dark(this, "YouTube");
        ytButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSocialApp("com.google.android.youtube", "YouTube"); }
        });
        row2.addView(ytButton);
        Button arStudioButton = UiStyle.primary(this, "AR Studio");
        arStudioButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AppState.isRecording) {
                    Toast.makeText(MainActivity.this, "Stop recording before opening AR Studio.", Toast.LENGTH_LONG).show();
                    return;
                }
                releaseCamera();
                startActivity(new Intent(MainActivity.this, ArCoreFaceActivity.class));
            }
        });
        row2.addView(arStudioButton);
        root.addView(row2);

        LinearLayout row2b = new LinearLayout(this);
        row2b.setOrientation(LinearLayout.HORIZONTAL);
        Button tiktokButton = UiStyle.dark(this, "TikTok");
        tiktokButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSocialApp("com.zhiliaoapp.musically", "TikTok"); }
        });
        row2b.addView(tiktokButton);
        Button clipsButton = UiStyle.dark(this, "Clips");
        clipsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showClips(); }
        });
        row2b.addView(clipsButton);
        Button shareButton = UiStyle.dark(this, "Share");
        shareButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareLastClip(); }
        });
        row2b.addView(shareButton);
        root.addView(row2b);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button capButton = UiStyle.dark(this, "Captions");
        capButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCaptions(); }
        });
        row3.addView(capButton);
        langButton = UiStyle.dark(this, "Lang: " + AppState.langName());
        langButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextLang();
                langButton.setText("Lang: " + AppState.langName());
                savePrefs();
                if (speechOn) { stopCaptionListening(); startCaptionListening(); }
            }
        });
        row3.addView(langButton);
        textColorButton = UiStyle.dark(this, "Text: " + AppState.textColorName());
        textColorButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextTextColor();
                textColorButton.setText("Text: " + AppState.textColorName());
                teleprompterView.setTextColor(AppState.textColor());
                scriptEdit.setTextColor(AppState.textColor());
                savePrefs();
            }
        });
        row3.addView(textColorButton);
        root.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        captionColorButton = UiStyle.dark(this, "Caption: " + AppState.captionColorName());
        captionColorButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextCaptionColor();
                captionColorButton.setText("Caption: " + AppState.captionColorName());
                captionView.setTextColor(AppState.captionColor());
                savePrefs();
            }
        });
        row4.addView(captionColorButton);
        // v0.7.0: the old Sound button played synthesized beeps, the user
        // said that music was awful. It is replaced by real music, in the
        // Music section below. This button only turns music in a clip on or off.
        musicToggleButton = UiStyle.dark(this, useMusic ? "Music: On" : "Music: Off");
        musicToggleButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useMusic = !useMusic;
                musicToggleButton.setText(useMusic ? "Music: On" : "Music: Off");
                savePrefs();
                if (!useMusic) MusicEngine.stop();
                statusView.setText(useMusic ? "Music in clip: " + MusicCatalog.TRACKS[musicTrackIndex].title : "Music in clip: Off. See Music below.");
            }
        });
        row4.addView(musicToggleButton);
        final Button speedButton = UiStyle.dark(this, "Speed 1x");
        speedButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AppState.speed < 1.0f) AppState.speed = 1.0f;
                else if (AppState.speed < 1.5f) AppState.speed = 1.5f;
                else AppState.speed = 0.5f;
                speedButton.setText("Speed " + AppState.speed + "x");
                savePrefs();
            }
        });
        row4.addView(speedButton);
        root.addView(row4);

        scriptEdit = new EditText(this);
        scriptEdit.setText(AppState.script);
        scriptEdit.setHint("Write your script here...");
        scriptEdit.setHintTextColor(0xFF888888);
        scriptEdit.setTextColor(AppState.textColor());
        scriptEdit.setTextSize(16);
        scriptEdit.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(160, Math.min(520, scriptEditorHeight))));
        scriptEdit.setMinLines(5);
        scriptEdit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        // Avoid null themed scrollbar drawable crashes; text editing and scrolling still work.
        scriptEdit.setVerticalScrollBarEnabled(false);
        scriptEdit.setPadding(16, 16, 16, 16);
        root.addView(scriptEdit);
        LinearLayout editorSizeRow = new LinearLayout(this);
        editorSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button editorSmaller = UiStyle.dark(this, "Editor −");
        editorSmaller.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resizeScriptEditor(-60); }
        });
        editorSizeRow.addView(editorSmaller);
        Button editorLarger = UiStyle.dark(this, "Editor +");
        editorLarger.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resizeScriptEditor(60); }
        });
        editorSizeRow.addView(editorLarger);
        root.addView(editorSizeRow);

        // v0.7.0 Music section, on its own. Real songs, not beeps.
        // Play: test a track first, streams if not downloaded yet.
        // Download: saves the MP3 locally in Music/TalkReelAR.
        // Use: picks this track for the next clip, with Music: On above.
        TextView musicTitle = new TextView(this);
        musicTitle.setText("Music");
        musicTitle.setTextColor(0xFFFFFFFF);
        musicTitle.setTextSize(18);
        musicTitle.setPadding(8, 16, 8, 4);
        root.addView(musicTitle);
        musicStatusView = new TextView(this);
        musicStatusView.setText("Tap Play to test a track. Tap Download to save it on this phone.");
        musicStatusView.setTextColor(0xFFAAAAAA);
        musicStatusView.setTextSize(13);
        musicStatusView.setPadding(8, 4, 8, 8);
        root.addView(musicStatusView);
        LinearLayout musicList = new LinearLayout(this);
        musicList.setOrientation(LinearLayout.VERTICAL);
        SafeScrollView musicScroll = new SafeScrollView(this);
        
        // Avoid Android framework crashes when the themed scrollbar drawable is null.
        // Scrolling remains enabled; only scrollbar rendering is disabled.
        musicScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 320));
        musicScroll.setVerticalScrollBarEnabled(false);
        musicScroll.setBackground(UiStyle.panelBg());
        musicScroll.addView(musicList);
        root.addView(musicScroll);
        for (int ti = 0; ti < MusicCatalog.TRACKS.length; ti++) {
            final MusicCatalog.Track track = MusicCatalog.TRACKS[ti];
            final int trackIndex = ti;
            TextView trackView = new TextView(this);
            String lic = track.license;
            if (track.credit != null && !track.credit.isEmpty()) lic = lic + ". Credit: " + track.credit;
            trackView.setText(track.title + " - " + track.artist + "\n" + track.mood + ", " + track.useFor + ", " + track.duration + "\n" + lic);
            trackView.setTextColor(0xFFFFFFFF);
            trackView.setTextSize(14);
            trackView.setPadding(8, 8, 8, 4);
            musicList.addView(trackView);
            LinearLayout musicRow = new LinearLayout(this);
            musicRow.setOrientation(LinearLayout.HORIZONTAL);
            Button playButton = UiStyle.dark(this, "Play");
            playButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    musicStatusView.setText("Playing: " + track.title + ". Tap Stop to stop it.");
                    MusicEngine.play(MainActivity.this, track, false, mainHandler, new MusicEngine.Callback() {
                        public void onResult(boolean ok, String message) { musicStatusView.setText(message); }
                    });
                }
            });
            musicRow.addView(playButton);
            Button stopMusicButton = UiStyle.dark(this, "Stop");
            stopMusicButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { MusicEngine.stop(); musicStatusView.setText("Music stopped."); }
            });
            musicRow.addView(stopMusicButton);
            Button downloadButton = UiStyle.dark(this, MusicEngine.isDownloaded(MainActivity.this, track) ? "Downloaded" : "Download");
            downloadButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    musicStatusView.setText("Downloading: " + track.title + "...");
                    MusicEngine.download(MainActivity.this, track, mainHandler, new MusicEngine.Callback() {
                        public void onResult(boolean ok, String message) {
                            musicStatusView.setText(message);
                            if (ok) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
            musicRow.addView(downloadButton);
            Button useButton = UiStyle.dark(this, "Use");
            useButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    musicTrackIndex = trackIndex;
                    useMusic = true;
                    if (musicToggleButton != null) musicToggleButton.setText("Music: On");
                    savePrefs();
                    musicStatusView.setText("Music in clip: " + track.title + ". Turn Music off above to stop using it.");
                }
            });
            musicRow.addView(useButton);
            musicList.addView(musicRow);
        }
        TextView musicNote = new TextView(this);
        musicNote.setText("Sources: Pixabay Music, free, no attribution, and Kevin MacLeod, CC BY 4.0, credit required. See docs/MUSIC.md for every track, license, and link. Music plays through the speaker while you record, it is not mixed into the video file yet.");
        musicNote.setTextColor(0xFFAAAAAA);
        musicNote.setTextSize(12);
        musicNote.setPadding(8, 8, 8, 8);
        root.addView(musicNote);

        SafeScrollView outer = new SafeScrollView(this);
        outer.setVerticalScrollBarEnabled(false);
        
        // Avoid Android framework crashes when the themed scrollbar drawable is null.
        // Scrolling remains enabled; only scrollbar rendering is disabled.
        outer.addView(root);
        setContentView(outer);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                // v0.6.1: if the camera is already open, restart the
                // preview on the new surface. openCamera() alone
                // returns early when cameraDevice != null, so the
                // preview stayed black after a surface recreate.
                if (cameraDevice != null) startPreview(); else openCamera();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception e) {}
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // v0.6.1: the camera was tried before the permission dialog
        // finished, so it failed and never retried, black preview.
        // Retry once here, after the user answers.
        boolean cameraOk = false;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.CAMERA.equals(permissions[i]) && i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED) cameraOk = true;
        }
        if (requestCode == 2) {
            boolean micOk = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (micOk) startRecording();
            else {
                recordingStarting = false;
                statusView.setText("Microphone permission denied. Recording needs audio access.");
                Toast.makeText(this, "Allow microphone access to record video with sound.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (cameraOk) {
            mainHandler.postDelayed(new Runnable() { public void run() { openCamera(); } }, 200);
        } else {
            statusView.setText("Camera permission is needed. Allow Camera, then reopen the app.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v0.6.1: reopen if the camera is missing for any reason,
        // not only after Instagram or YouTube. That covers the
        // first launch after the permission dialog, and a surface
        // recreate. Guarded by cameraOpening, and only once per
        // resume, so it cannot loop.
        if (cameraDevice == null && textureView != null && textureView.isAvailable()) {
            boolean hadReleased = cameraReleasedForSocial;
            cameraReleasedForSocial = false;
            mainHandler.postDelayed(new Runnable() {
                public void run() { openCamera(); }
            }, hadReleased ? 300 : 100);
        }
        if (frameView != null) frameView.postInvalidate();
    }

    private void resizeScriptEditor(int delta) {
        if (scriptEdit == null) return;
        ViewGroup.LayoutParams lp = scriptEdit.getLayoutParams();
        lp.height = Math.max(160, Math.min(520, lp.height + delta));
        scriptEditorHeight = lp.height;
        scriptEdit.setLayoutParams(lp);
        savePrefs();
    }

    private void savePrefs() {
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        prefs.edit().putString("script", AppState.script).putFloat("speed", AppState.speed).putInt("langMode", AppState.langMode).putBoolean("showFrame", AppState.showFrame).putBoolean("isPro", AppState.isPro).putInt("textColor", AppState.textColorIndex).putInt("captionColor", AppState.captionColorIndex).putInt("musicTrack", musicTrackIndex).putBoolean("useMusic", useMusic).putInt("scriptEditorHeight", scriptEditorHeight).putBoolean("faceOn", preferFrontCamera).putInt("arFilter", AppState.arFilter).apply();
    }

    private void releaseCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
        } catch (Exception e) {}
        try {
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        } catch (Exception e) {}
        cameraOpening = false;
        cameraReleasedForSocial = true;
    }

    private void startOverlay() {
        AppState.script = scriptEdit.getText().toString();
        savePrefs();
        if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow TalkReel over other apps, then tap Overlay again.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        if (!AppState.isRecording) releaseCamera();
        Intent service = new Intent(this, TalkReelService.class);
        service.setAction(TalkReelService.ACTION_START_OVERLAY);
        try {
            startForegroundService(service);
            Toast.makeText(this, "Overlay on. Open Instagram or YouTube.", Toast.LENGTH_LONG).show();
            statusView.setText("Overlay Companion on. Use the social app to record; Face mode is controlled there.");
        } catch (Exception e) {
            Toast.makeText(this, "Overlay failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openSocialApp(String pkg, String appName) {
        AppState.script = scriptEdit.getText().toString();
        savePrefs();
        if (!AppState.isRecording) releaseCamera();
        String launchError = null;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                statusView.setText("Opening " + appName);
                return;
            }
        } catch (Exception e) {
            launchError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            statusView.setText("Opening " + appName);
            return;
        } catch (Exception e) {
            launchError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String message = launchError == null
            ? appName + " has no launchable activity. Check its app version."
            : "Could not open " + appName + ": " + launchError;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        statusView.setText(message);
        if (textureView != null && textureView.isAvailable()) openCamera();
    }

    private void showClips() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TalkReelAR");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            statusView.setText("No clips yet");
            Toast.makeText(this, "No clips yet. Record one first.", Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder sb = new StringBuilder(files.length + " clips:\n");
        for (File f : files) sb.append("- ").append(f.getName()).append("\n");
        statusView.setText(sb.toString());
    }

    private void shareLastClip() {
        if (AppState.lastFile == null || AppState.lastFile.isEmpty()) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TalkReelAR");
            File[] files = dir.listFiles();
            if (files != null && files.length > 0) {
                java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                AppState.lastFile = files[0].getAbsolutePath();
            }
        }
        if (AppState.lastFile == null || AppState.lastFile.isEmpty()) {
            Toast.makeText(this, "No clip to share yet.", Toast.LENGTH_LONG).show();
            return;
        }
        File clip = new File(AppState.lastFile);
        if (!clip.exists() || clip.length() == 0) {
            Toast.makeText(this, "That clip is empty or missing.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri contentUri = ClipProvider.uriForFile(clip);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("video/mp4");
            share.putExtra(Intent.EXTRA_STREAM, contentUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newUri(getContentResolver(), "TalkReel clip", contentUri));
            startActivity(Intent.createChooser(share, "Share clip"));
            statusView.setText("Sharing " + clip.getName());
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleCaptions() {
        if (speechOn) {
            speechOn = false;
            stopCaptionListening();
            captionView.setText("");
            statusView.setText("Captions off");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            captionView.setText("Speech recognition is not available on this phone.");
            return;
        }
        speechOn = true;
        restartCount = 0;
        windowStartAt = System.currentTimeMillis();
        wordsInWindow = 0;
        currentCaptionLang = AppState.langCode();
        startCaptionListening();
        statusView.setText("Captions on, " + AppState.langName());
    }

    private void stopCaptionListening() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception e) {}
            speechRecognizer = null;
        }
    }

    private void startCaptionListening() {
        stopCaptionListening();
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) { return; }
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {}
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {}
            public void onError(int e) {
                if (!speechOn) return;
                if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_SERVER) {
                    restartCount++;
                    if (restartCount > 30) { speechOn = false; return; }
                    mainHandler.postDelayed(new Runnable() {
                        public void run() { if (speechOn) startCaptionListening(); }
                    }, 400);
                }
            }
            public void onResults(Bundle r) {
                handleCaptionBundle(r, false);
                if (speechOn) {
                    mainHandler.postDelayed(new Runnable() {
                        public void run() { if (speechOn) startCaptionListening(); }
                    }, 150);
                }
            }
            public void onPartialResults(Bundle r) { handleCaptionBundle(r, true); }
            public void onEvent(int e, Bundle b) {}
        });
        restartListeningIntent();
    }

    private void restartListeningIntent() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            String lang = currentCaptionLang;
            if (lang == null || lang.isEmpty()) lang = AppState.langCode();
            if (lang != null && !lang.isEmpty()) {
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            }
            speechRecognizer.startListening(i);
        } catch (Exception e) {}
    }

    private void handleCaptionBundle(Bundle r, boolean partial) {
        ArrayList<String> list = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        String text = list.get(0);
        if (text == null || text.trim().isEmpty()) return;
        lastCaptionAt = System.currentTimeMillis();
        wordsInWindow += text.trim().split("\\s+").length;
        CaptionStore.add(AppState.seconds, text);
        final String shown = text;
        mainHandler.post(new Runnable() {
            public void run() {
                captionView.setText(shown);
                captionView.setTextColor(AppState.captionColor());
                if (LanguageDetector.isArabicScript(shown)) captionView.setTextDirection(View.TEXT_DIRECTION_RTL);
                else captionView.setTextDirection(View.TEXT_DIRECTION_LTR);
            }
        });
        if (AppState.langMode == 0) {
            String detected = LanguageDetector.detect(text);
            if (!detected.isEmpty() && !detected.equals(currentCaptionLang)) {
                long now = System.currentTimeMillis();
                if (now - lastLangSwitchAt > 3000) {
                    lastLangSwitchAt = now;
                    currentCaptionLang = detected;
                    AppState.captionLang = detected;
                    final String d = detected;
                    mainHandler.post(new Runnable() {
                        public void run() { statusView.setText("Captions: " + d); }
                    });
                    mainHandler.postDelayed(new Runnable() {
                        public void run() { if (speechOn) startCaptionListening(); }
                    }, 300);
                }
            }
        }
    }

    private void togglePause() {
        if (!AppState.isRecording) return;
        AppState.isPaused = !AppState.isPaused;
        pauseButton.setText(AppState.isPaused ? "Resume" : "Pause");
        if (AppState.isPaused) {
            if (mediaRecorder != null) { try { mediaRecorder.pause(); } catch (Exception e) {} }
            statusView.setText("Paused");
        } else {
            if (mediaRecorder != null) { try { mediaRecorder.resume(); } catch (Exception e) {} }
            statusView.setText("Resumed");
            mainHandler.post(ticker);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!AppState.isRecording) startRecording(); else stopRecording();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private Rect activeArray = null;
    private boolean isFrontCamera = true;
    private CameraCaptureSession.CaptureCallback faceCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
            android.hardware.camera2.params.Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
            if (faces != null && faces.length > 0 && frameView != null) {
                List<Rect> rects = new ArrayList<Rect>();
                int vw = frameView.getWidth();
                int vh = frameView.getHeight();
                if (vw > 0 && vh > 0) {
                    for (android.hardware.camera2.params.Face f : faces) {
                        Rect r = f.getBounds();
                        Rect mapped;
                        if (activeArray != null && activeArray.width() > 0 && activeArray.height() > 0) {
                            int left = (r.left - activeArray.left) * vw / activeArray.width();
                            int top = (r.top - activeArray.top) * vh / activeArray.height();
                            int right = (r.right - activeArray.left) * vw / activeArray.width();
                            int bottom = (r.bottom - activeArray.top) * vh / activeArray.height();
                            if (isFrontCamera) {
                                int ml = vw - right;
                                int mr = vw - left;
                                left = ml;
                                right = mr;
                            }
                            mapped = new Rect(left, top, right, bottom);
                        } else {
                            mapped = new Rect(r.left * vw / 1000, r.top * vh / 1000, r.right * vw / 1000, r.bottom * vh / 1000);
                        }
                        rects.add(mapped);
                    }
                    frameView.setFaces(rects);
                    return;
                }
            }
            if (frameView != null) frameView.clearFaces();
        }
    };

    private void openCamera() {
        // Guard: one open at a time. This stops the open/close loop.
        if (cameraOpening || cameraDevice != null) return;
        if (textureView == null || !textureView.isAvailable()) return;
        cameraOpening = true;
        cameraHandler.post(new Runnable() {
            public void run() {
                CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                try {
                    String cameraId = null;
                    for (String id : manager.getCameraIdList()) {
                        CameraCharacteristics c = manager.getCameraCharacteristics(id);
                        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == (preferFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) { cameraId = id; break; }
                    }
                    if (cameraId == null) cameraId = manager.getCameraIdList()[0];
                    CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                    activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    isFrontCamera = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { cameraOpening = false; return; }
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        public void onOpened(CameraDevice camera) {
                            cameraOpening = false;
                            cameraDevice = camera;
                            startPreview();
                        }
                        public void onDisconnected(CameraDevice camera) {
                            cameraOpening = false;
                            camera.close();
                            if (cameraDevice == camera) cameraDevice = null;
                        }
                        public void onError(CameraDevice camera, int error) {
                            cameraOpening = false;
                            camera.close();
                            if (cameraDevice == camera) cameraDevice = null;
                            mainHandler.post(new Runnable() {
                                public void run() { statusView.setText("Camera busy. Close Instagram camera first."); }
                            });
                        }
                    }, cameraHandler);
                } catch (Exception e) {
                    cameraOpening = false;
                    mainHandler.post(new Runnable() {
                        public void run() { statusView.setText("Camera error"); }
                    });
                }
            }
        });
    }

    private void startPreview() {
        try {
            if (cameraDevice == null) return;
            SurfaceTexture tex = textureView.getSurfaceTexture();
            if (tex == null) return;
            tex.setDefaultBufferSize(720, 1280);
            Surface surface = new Surface(tex);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try { session.setRepeatingRequest(previewBuilder.build(), faceCallback, cameraHandler); } catch (Exception e) {}
                }
                public void onConfigureFailed(CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (Exception e) {}
    }

    private int recordWaitCount = 0;
    private boolean recordingStarting = false;
    private void startRecording() {
        if (AppState.isRecording || recordingStarting) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            statusView.setText("Microphone permission is needed to record.");
            return;
        }
        recordingStarting = true;
        statusView.setText("Preparing recording…");
        recordButton.setEnabled(false);
        // v0.6.1: if the camera is not open yet, wait for it instead
        // of starting MediaRecorder with no camera, which left the
        // app in REC with no video. Retry up to 5 times, 400ms apart.
        if (cameraDevice == null) {
            recordButton.setEnabled(true);
            openCamera();
            if (recordWaitCount < 5) {
                recordingStarting = false;
                recordWaitCount++;
                statusView.setText("Opening camera...");
                mainHandler.postDelayed(new Runnable() { public void run() { startRecording(); } }, 400);
                return;
            }
            recordWaitCount = 0;
            recordingStarting = false;
            recordButton.setEnabled(true);
            Toast.makeText(this, "Camera is not ready. Close Instagram camera first, then try again.", Toast.LENGTH_LONG).show();
            return;
        }
        recordWaitCount = 0;
        AppState.script = scriptEdit.getText().toString();
        savePrefs();
        teleprompterView.setText(AppState.script);
        teleprompterView.setTextColor(AppState.textColor());
        CaptionStore.reset();
        SilenceLog.reset();
        quietSeconds = 0;
        // Foreground service only, no overlay. Overlay is only
        // shown when the user taps Overlay. That was the loop.
        Intent service = new Intent(this, TalkReelService.class);
        service.setAction(TalkReelService.ACTION_START_SERVICE);
        try { startForegroundService(service); } catch (Exception e) {}
        cameraHandler.post(new Runnable() {
            public void run() {
                try {
                    mediaRecorder = new MediaRecorder();
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mediaRecorder.setVideoSize(AppState.exportWidth(), AppState.exportHeight());
                    mediaRecorder.setVideoFrameRate(30);
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TalkReelAR");
                    dir.mkdirs();
                    String name = "TalkReel-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date()) + ".mp4";
                    File out = new File(dir, name);
                    AppState.lastFile = out.getAbsolutePath();
                    mediaRecorder.setOutputFile(out.getAbsolutePath());
                    mediaRecorder.prepare();
                    final Surface recorderSurface = mediaRecorder.getSurface();
                    mainHandler.post(new Runnable() {
                        public void run() {
                            try {
                                SurfaceTexture tex = textureView.getSurfaceTexture();
                                if (tex == null || cameraDevice == null) throw new IllegalStateException("Camera preview surface is unavailable.");
                                tex.setDefaultBufferSize(AppState.exportWidth(), AppState.exportHeight());
                                Surface previewSurface = new Surface(tex);
                                previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                previewBuilder.addTarget(previewSurface);
                                previewBuilder.addTarget(recorderSurface);
                                previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                                previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
                                List<Surface> surfaces = new ArrayList<Surface>();
                                surfaces.add(previewSurface);
                                surfaces.add(recorderSurface);
                                cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                                    public void onConfigured(CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            session.setRepeatingRequest(previewBuilder.build(), faceCallback, cameraHandler);
                                            mediaRecorder.start();
                                            AppState.isRecording = true;
                                            AppState.isPaused = false;
                                            AppState.seconds = 0;
                                            recordingStarting = false;
                                            windowStartAt = System.currentTimeMillis();
                                            wordsInWindow = 0;
                                            mainHandler.post(new Runnable() {
                                                public void run() {
                                                    if (useMusic && musicTrackIndex >= 0 && musicTrackIndex < MusicCatalog.TRACKS.length) {
                                                        MusicEngine.play(MainActivity.this, MusicCatalog.TRACKS[musicTrackIndex], true, mainHandler, null);
                                                    }
                                                    recordButton.setText("Stop");
                                                    recordButton.setEnabled(true);
                                                    pauseButton.setEnabled(true);
                                                    statusView.setText("REC 0s  " + AppState.exportLabel());
                                                    mainHandler.post(ticker);
                                                }
                                            });
                                        } catch (final Exception e) {
                                            failRecording(e);
                                        }
                                    }
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        failRecording(new Exception("The camera could not configure the recording session."));
                                    }
                                }, cameraHandler);
                            } catch (final Exception e) {
                                failRecording(e);
                            }
                        }
                    });
                } catch (final Exception e) {
                    failRecording(e);
                }
            }
        });
    }

    private void failRecording(final Exception error) {
        recordingStarting = false;
        AppState.isRecording = false;
        AppState.isPaused = false;
        MusicEngine.stop();
        if (cameraHandler != null) {
            cameraHandler.post(new Runnable() {
                public void run() {
                    try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                    try {
                        if (mediaRecorder != null) {
                            try { mediaRecorder.stop(); } catch (Exception ignored) {}
                            mediaRecorder.reset();
                            mediaRecorder.release();
                            mediaRecorder = null;
                        }
                    } catch (Exception ignored) {}
                    final File failed = AppState.lastFile == null ? null : new File(AppState.lastFile);
                    if (failed != null && failed.exists() && failed.length() == 0) failed.delete();
                    mainHandler.post(new Runnable() {
                        public void run() {
                            if (recordButton != null) { recordButton.setText("Record"); recordButton.setEnabled(true); }
                            if (pauseButton != null) { pauseButton.setText("Pause"); pauseButton.setEnabled(false); }
                            String detail = error == null || error.getMessage() == null ? "Unknown camera error" : error.getMessage();
                            if (statusView != null) statusView.setText("Recording could not start: " + detail);
                            Toast.makeText(MainActivity.this, "Recording failed: " + detail, Toast.LENGTH_LONG).show();
                            stopService(new Intent(MainActivity.this, TalkReelService.class));
                            if (cameraDevice != null && textureView != null && textureView.isAvailable()) startPreview();
                        }
                    });
                }
            });
        }
    }

    private void stopRecording() {
        if (!AppState.isRecording) {
            if (pauseButton != null) pauseButton.setText("Pause");
            return;
        }
        MusicEngine.stop();
        mainHandler.removeCallbacks(ticker);
        final int finalSec = AppState.seconds;
        cameraHandler.post(new Runnable() {
            public void run() {
                Exception stopError = null;
                try {
                    if (mediaRecorder != null) { mediaRecorder.stop(); mediaRecorder.reset(); mediaRecorder.release(); mediaRecorder = null; }
                } catch (Exception e) {
                    stopError = e;
                    try { if (mediaRecorder != null) mediaRecorder.release(); } catch (Exception ignored) {}
                    mediaRecorder = null;
                }
                CaptionStore.finish(finalSec);
                SilenceLog.finish(finalSec);
                File clip = new File(AppState.lastFile);
                if (stopError != null && clip.exists() && clip.length() < 4096) clip.delete();
                String extra = "";
                if (AppState.isPro && clip.exists()) {
                    File srt = CaptionStore.write(clip);
                    File cut = SilenceLog.write(clip);
                    extra = "  SRT: " + CaptionStore.count() + " lines. Cuts: " + SilenceLog.count() + ".";
                }
                AppState.isRecording = false;
                AppState.isPaused = false;
                final String msg = extra;
                mainHandler.post(new Runnable() {
                    public void run() {
                        recordButton.setText("Record");
                        pauseButton.setText("Pause");
                        if (clip.exists() && clip.length() > 4096) {
                            statusView.setText("Saved to Movies/TalkReelAR." + msg);
                            Toast.makeText(MainActivity.this, "Clip saved. " + clip.getName(), Toast.LENGTH_LONG).show();
                        } else {
                            statusView.setText("Recording was too short or could not be finalized.");
                            Toast.makeText(MainActivity.this, "The clip could not be saved. Try recording again.", Toast.LENGTH_LONG).show();
                        }
                        stopService(new Intent(MainActivity.this, TalkReelService.class));
                        startPreview();
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(controlReceiver); } catch (Exception e) {}
        mainHandler.removeCallbacks(ticker);
        speechOn = false;
        stopCaptionListening();
        MusicEngine.stop();
        if (cameraThread != null) cameraThread.quitSafely();
        if (cameraDevice != null) cameraDevice.close();
    }
}
