package com.talkreel.ar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaRecorder;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.text.InputType;
import org.json.JSONObject;
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
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;

// v0.6 professional rebuild.
// Single owner for recording: this activity only.
// Camera: one open at a time, guarded by cameraOpening.
// Overlay: never shown automatically on Record. Only when
// the user taps Overlay. Overlay buttons only send a request,
// they never change state here.
public class MainActivity extends Activity {
    private volatile ArCompositor arCompositor;
    private volatile boolean activityDestroyed = false;
    private volatile boolean activityResumed = false;
    private volatile int faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
    private volatile int cameraRotationDegrees = 0;
    private volatile int cameraBufferWidth = 1280;
    private volatile int cameraBufferHeight = 720;
    private volatile int faceInputRotationDegrees = 0;
    private ImageReader faceImageReader;
    private FaceTrackingEngine faceTrackingEngine;
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
    private int teleprompterTextSize = 26;
    private int selfieFrameShape = 0;
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
    private final Set<String> unavailableCameraIds = new HashSet<String>();
    private volatile String selectedCameraId;
    private volatile boolean waitingForCamera = false;
    private boolean waitingToastShown = false;
    private boolean pendingRecordWhenCameraReady = false;
    private File pendingExportClip;
    private CameraManager cameraManager;
    private CameraManager.AvailabilityCallback cameraAvailabilityCallback;
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
        AppState.arFilter = Math.max(0, Math.min(ProFrameView.FILTER_COUNT - 1, prefs.getInt("arFilter", ProFrameView.FILTER_GUIDE)));
        AppState.isPro = prefs.getBoolean("isPro", false);
        AppState.textColorIndex = prefs.getInt("textColor", 0);
        AppState.captionColorIndex = prefs.getInt("captionColor", 1);
        teleprompterTextSize = prefs.getInt("teleprompterTextSize", 26);
        selfieFrameShape = prefs.getInt("selfieFrameShape", 0);
        musicTrackIndex = prefs.getInt("musicTrack", 0);
        scriptEditorHeight = prefs.getInt("scriptEditorHeight", 260);
        preferFrontCamera = prefs.getBoolean("faceOn", true);
        useMusic = prefs.getBoolean("useMusic", false);
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraAvailabilityCallback = new CameraManager.AvailabilityCallback() {
            @Override public void onCameraUnavailable(String cameraId) {
                synchronized (unavailableCameraIds) { unavailableCameraIds.add(cameraId); }
                if (cameraId.equals(selectedCameraId) && cameraDevice == null && !cameraOpening) showCameraWaiting();
            }
            @Override public void onCameraAvailable(String cameraId) {
                synchronized (unavailableCameraIds) { unavailableCameraIds.remove(cameraId); }
                if (cameraId.equals(selectedCameraId) && waitingForCamera) {
                    waitingForCamera = false;
                    waitingToastShown = false;
                    mainHandler.post(new Runnable() {
                        public void run() {
                            if (statusView != null) statusView.setText("Camera available · Reconnecting preview…");
                            if (textureView != null && textureView.isAvailable() && !AppState.isRecording) openCamera();
                        }
                    });
                }
            }
        };
        if (cameraManager != null) cameraManager.registerAvailabilityCallback(cameraAvailabilityCallback, mainHandler);

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
        statusView.setText("Preview ready · Not recording · " + AppState.exportLabel());
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
        frameView.setFrameShape(selfieFrameShape);
        previewBox.addView(frameView);
        root.addView(previewBox);
        TextView arNote = new TextView(this);
        arNote.setText("20 face-contour makeup and Halloween looks · Live preview only in this build; saved-video effect rendering is still in progress.");
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
        teleprompterView.setTextSize(teleprompterTextSize);
        teleprompterView.setLineSpacing(8, 1.35f);
        teleprompterView.setPadding(8, 8, 8, 8);
        teleprompterScroll.addView(teleprompterView);
        root.addView(teleprompterScroll);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = UiStyle.primary(this, "Live · Start recording");
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
        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button settingsButton = UiStyle.primary(this, "Main settings");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMainSettings(); }
        });
        settingsRow.addView(settingsButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button templatesButton = UiStyle.dark(this, "Templates");
        templatesButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTemplates(); }
        });
        settingsRow.addView(templatesButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(settingsRow);

        LinearLayout rowPro = new LinearLayout(this);
        rowPro.setOrientation(LinearLayout.HORIZONTAL);
        proButton = UiStyle.primary(this, AppState.isPro ? "Pro preview: On" : "Pro preview: Off");
        proButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.isPro = !AppState.isPro;
                proButton.setText(AppState.isPro ? "Pro preview: On" : "Pro preview: Off");
                savePrefs();
                coachView.setText(ScriptCoach.shortLine(scriptEdit.getText().toString()));
                statusView.setText(AppState.isPro ? "Pro on  " + AppState.exportLabel() : "Free  " + AppState.exportLabel());
            }
        });
        rowPro.addView(proButton);
        Button coachButton = UiStyle.dark(this, "Script coach");
        coachButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.script = scriptEdit.getText().toString();
                coachView.setText(ScriptCoach.shortLine(AppState.script));
                savePrefs();
            }
        });
        rowPro.addView(coachButton);
        final Button frameButton = UiStyle.dark(this, AppState.showFrame ? "Guides: On" : "Guides: Off");
        frameButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.showFrame = !AppState.showFrame;
                frameButton.setText(AppState.showFrame ? "Guides: On" : "Guides: Off");
                savePrefs();
                if (frameView != null) frameView.postInvalidate();
            }
        });
        rowPro.addView(frameButton);
        final Button faceButton = UiStyle.dark(this, preferFrontCamera ? "Front camera" : "Rear camera");
        faceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AppState.isRecording) {
                    Toast.makeText(MainActivity.this, "Stop the clip before changing Face mode.", Toast.LENGTH_SHORT).show();
                    return;
                }
                preferFrontCamera = !preferFrontCamera;
                faceButton.setText(preferFrontCamera ? "Front camera" : "Rear camera");
                savePrefs();
                releaseCamera();
                statusView.setText(preferFrontCamera ? "Face mode on: front camera" : "Face mode off: rear camera");
                mainHandler.postDelayed(new Runnable() { public void run() { openCamera(); } }, 350);
            }
        });
        rowPro.addView(faceButton);
        final Button arFilterButton = UiStyle.dark(this, "Look: " + ProFrameView.filterName(AppState.arFilter));
        arFilterButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.arFilter = (AppState.arFilter + 1) % ProFrameView.FILTER_COUNT;
                frameView.setFilter(AppState.arFilter);
                if (arCompositor != null) arCompositor.setFilter(AppState.arFilter % 6);
                arFilterButton.setText("Look: " + ProFrameView.filterName(AppState.arFilter));
                savePrefs();
                statusView.setText("Look: " + ProFrameView.filterName(AppState.arFilter) + " · Face-tracked preview overlay");
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
        root.addView(row2);

        LinearLayout row2b = new LinearLayout(this);
        row2b.setOrientation(LinearLayout.HORIZONTAL);
        Button tiktokButton = UiStyle.dark(this, "TikTok");
        tiktokButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSocialApp("com.zhiliaoapp.musically", "TikTok"); }
        });
        row2b.addView(tiktokButton);
        Button clipsButton = UiStyle.dark(this, "Review videos");
        clipsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showClips(); }
        });
        row2b.addView(clipsButton);
        Button shareButton = UiStyle.dark(this, "Share video");
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
        langButton = UiStyle.dark(this, "Caption language: " + AppState.langName());
        langButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextLang();
                langButton.setText("Caption language: " + AppState.langName());
                savePrefs();
                if (speechOn) { stopCaptionListening(); startCaptionListening(); }
            }
        });
        row3.addView(langButton);
        textColorButton = UiStyle.dark(this, "Prompter color: " + AppState.textColorName());
        textColorButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextTextColor();
                textColorButton.setText("Prompter color: " + AppState.textColorName());
                teleprompterView.setTextColor(AppState.textColor());
                scriptEdit.setTextColor(AppState.textColor());
                savePrefs();
            }
        });
        row3.addView(textColorButton);
        root.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        captionColorButton = UiStyle.dark(this, "Caption color: " + AppState.captionColorName());
        captionColorButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextCaptionColor();
                captionColorButton.setText("Caption color: " + AppState.captionColorName());
                captionView.setTextColor(AppState.captionColor());
                savePrefs();
            }
        });
        row4.addView(captionColorButton);
        // v0.7.0: the old Sound button played synthesized beeps, the user
        // said that music was awful. It is replaced by real music, in the
        // Music section below. This button only turns music in a clip on or off.
        musicToggleButton = UiStyle.dark(this, useMusic ? "Music in recording: On" : "Music in recording: Off");
        musicToggleButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useMusic = !useMusic;
                musicToggleButton.setText(useMusic ? "Music in recording: On" : "Music in recording: Off");
                savePrefs();
                if (!useMusic) MusicEngine.stop();
                statusView.setText(useMusic ? "Music in clip: " + MusicCatalog.TRACKS[musicTrackIndex].title : "Music in clip: Off. See Music below.");
            }
        });
        row4.addView(musicToggleButton);
        final Button speedButton = UiStyle.dark(this, "Scroll speed 1x");
        speedButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (AppState.speed < 1.0f) AppState.speed = 1.0f;
                else if (AppState.speed < 1.5f) AppState.speed = 1.5f;
                else AppState.speed = 0.5f;
                speedButton.setText("Scroll speed " + AppState.speed + "x");
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
        Button editorSmaller = UiStyle.dark(this, "Script editor −");
        editorSmaller.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resizeScriptEditor(-60); }
        });
        editorSizeRow.addView(editorSmaller);
        Button editorLarger = UiStyle.dark(this, "Script editor +");
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
            Button useButton = UiStyle.dark(this, "Select track");
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
        musicNote.setText(isMedia3ExportAvailable()
                ? "Download the selected track before recording. TalkReel mixes downloaded music under the microphone audio during finished-video export. Track licenses and attribution: docs/MUSIC.md."
                : "This standalone build can preview music only. Digital mixing requires the Media3-enabled Gradle build. Track licenses: docs/MUSIC.md.");
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
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            outer.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public android.view.WindowInsets onApplyWindowInsets(View view, android.view.WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout()
                                    | android.view.WindowInsets.Type.ime());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                }
            });
        }

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                // v0.6.1: if the camera is already open, restart the
                // preview on the new surface. openCamera() alone
                // returns early when cameraDevice != null, so the
                // preview stayed black after a surface recreate.
                if (cameraDevice != null && cameraHandler != null) cameraHandler.post(new Runnable() { public void run() { startPreview(); } }); else openCamera();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                if (cameraHandler != null) cameraHandler.post(new Runnable() {
                    public void run() {
                        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                        if (arCompositor != null) { try { arCompositor.stop(); } catch (Exception ignored) {} arCompositor = null; }
                    }
                });
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
        // Request camera only after the main UI is attached. Microphone permission
        // is requested on the explicit Record action, not during app startup.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else if (textureView.isAvailable()) {
            openCamera();
        }
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
        if (requestCode == 42) {
            File clip = pendingExportClip;
            pendingExportClip = null;
            if (clip != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) exportLegacyVideoToPhone(clip);
            else if (clip != null) Toast.makeText(this, "Storage permission denied. Video was not exported.", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == 43) {
            boolean audioOk = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (audioOk) startRecording();
            else Toast.makeText(this, "Microphone permission denied. Recording was not started.", Toast.LENGTH_LONG).show();
            return;
        }
        if (cameraOk) {
            mainHandler.postDelayed(new Runnable() { public void run() { openCamera(); } }, 200);
        } else {
            statusView.setText("Camera permission is needed. Allow Camera, then reopen the app.");
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (!AppState.isRecording) releaseCamera();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
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

    private TextView settingsLabel(String value) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13); t.setPadding(0,10,0,2); return t;
    }
    private int safeInt(String s, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(s.trim()))); } catch(Exception e) { return fallback; }
    }
    private float safeFloat(String s, float fallback) {
        try { return Math.max(0.5f, Math.min(2.0f, Float.parseFloat(s.trim()))); } catch(Exception e) { return fallback; }
    }
    private void applyCreatorSettings(String script, int size, int ar, int music, int shape, float speed) {
        AppState.script = script; scriptEdit.setText(script); teleprompterView.setText(script);
        teleprompterTextSize = size; teleprompterView.setTextSize(size);
        AppState.arFilter = ar; frameView.setFilter(ar); if (arCompositor != null) arCompositor.setFilter(ar % 6);
        musicTrackIndex = Math.max(0, Math.min(music, MusicCatalog.TRACKS.length - 1));
        selfieFrameShape = shape; frameView.setFrameShape(shape);
        AppState.speed = speed; savePrefs();
        if (musicToggleButton != null) musicToggleButton.setText(useMusic ? "Music in recording: On" : "Music in recording: Off");
        statusView.setText("Settings applied · Preview ready · Not recording");
    }
    private void showMainSettings() {
        final LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 8, 24, 8);
        final EditText script = new EditText(this); script.setText(scriptEdit.getText().toString());
        script.setHint("Teleprompter script"); script.setMinLines(4); script.setGravity(android.view.Gravity.TOP);
        form.addView(settingsLabel("Teleprompter text")); form.addView(script);
        final EditText size = new EditText(this); size.setSingleLine(true); size.setInputType(InputType.TYPE_CLASS_NUMBER);
        size.setText(String.valueOf(teleprompterTextSize)); form.addView(settingsLabel("Teleprompter text size (12–64 sp)")); form.addView(size);
        final Spinner ar = new Spinner(this); String[] effects = new String[ProFrameView.FILTER_COUNT];
        for(int i=0;i<effects.length;i++) effects[i]=ProFrameView.filterName(i);
        ar.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, effects));
        ar.setSelection(AppState.arFilter); form.addView(settingsLabel("Face-tracked makeup & Halloween looks")); form.addView(ar);
        final Spinner music = new Spinner(this); String[] tracks = new String[MusicCatalog.TRACKS.length];
        for(int i=0;i<tracks.length;i++) tracks[i]=MusicCatalog.TRACKS[i].title;
        music.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, tracks));
        music.setSelection(Math.max(0,Math.min(musicTrackIndex,tracks.length-1)));
        form.addView(settingsLabel("Music track")); form.addView(music);
        final Spinner shape = new Spinner(this); String[] shapes = {"Rounded frame","Circle","Oval","Portrait oval"};
        shape.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, shapes));
        shape.setSelection(Math.max(0,Math.min(selfieFrameShape,shapes.length-1)));
        form.addView(settingsLabel("Selfie frame shape")); form.addView(shape);
        final EditText speed = new EditText(this); speed.setSingleLine(true);
        speed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setText(String.valueOf(AppState.speed)); form.addView(settingsLabel("Teleprompter scroll speed (0.5–2.0)")); form.addView(speed);
        new AlertDialog.Builder(this).setTitle("Main settings").setView(form)
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Apply",(d,w)->applyCreatorSettings(script.getText().toString(),
                safeInt(size.getText().toString(),26,12,64),ar.getSelectedItemPosition(),music.getSelectedItemPosition(),
                shape.getSelectedItemPosition(),safeFloat(speed.getText().toString(),1.0f)))
            .setPositiveButton("Save as template",(d,w)->{
                applyCreatorSettings(script.getText().toString(),safeInt(size.getText().toString(),26,12,64),
                    ar.getSelectedItemPosition(),music.getSelectedItemPosition(),shape.getSelectedItemPosition(),
                    safeFloat(speed.getText().toString(),1.0f));
                final EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Template name");
                new AlertDialog.Builder(this).setTitle("Save template").setView(name).setNegativeButton("Cancel",null)
                    .setPositiveButton("Save",(dd,ww)->{
                        String n=name.getText().toString().trim(); if(n.isEmpty()) n="My template";
                        if ("Default".equalsIgnoreCase(n)) {
                            Toast.makeText(this, "Default is reserved. Choose another template name.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            JSONObject o=new JSONObject(); o.put("schemaVersion",1); o.put("script",AppState.script); o.put("size",teleprompterTextSize);
                            o.put("ar",AppState.arFilter); o.put("music",musicTrackIndex); o.put("musicEnabled",useMusic); o.put("shape",selfieFrameShape); o.put("speed",AppState.speed);
                            getSharedPreferences("talkreel_templates",MODE_PRIVATE).edit().putString("template:"+n,o.toString()).apply();
                            Toast.makeText(this,"Template saved: "+n,Toast.LENGTH_SHORT).show();
                        } catch(Exception e) { Toast.makeText(this,"Could not save template.",Toast.LENGTH_LONG).show(); }
                    }).show();
            }).show();
    }
    private void showTemplates() {
        final SharedPreferences prefs = getSharedPreferences("talkreel_templates", MODE_PRIVATE);
        final java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("Default");
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("template:")) names.add(key.substring(9));
        }
        final String[] items = names.toArray(new String[names.size()]);
        new AlertDialog.Builder(this).setTitle("Templates").setItems(items, (d, which) -> {
            final String selected = items[which];
            if ("Default".equals(selected)) {
                new AlertDialog.Builder(this).setTitle("Default template")
                        .setMessage("Restore the built-in starter setup?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply", (dd, ww) -> {
                            AppState.script = "";
                            useMusic = false;
                            applyCreatorSettings("", 26, ProFrameView.FILTER_GUIDE, 0, 0, 1.0f);
                            savePrefs();
                            Toast.makeText(this, "Default template applied.", Toast.LENGTH_SHORT).show();
                        }).show();
                return;
            }
            final String[] actions = {"Apply", "Update with current settings", "Duplicate", "Delete"};
            new AlertDialog.Builder(this).setTitle(selected).setItems(actions, (ad, action) -> {
                try {
                    final String key = "template:" + selected;
                    JSONObject stored = new JSONObject(prefs.getString(key, "{}"));
                    if (action == 0) {
                        applyCreatorSettings(stored.optString("script", AppState.script), stored.optInt("size", 26),
                                stored.optInt("ar", 0), stored.optInt("music", 0), stored.optInt("shape", 0),
                                (float) stored.optDouble("speed", 1.0));
                        useMusic = stored.optBoolean("musicEnabled", false);
                        if (musicToggleButton != null) musicToggleButton.setText(useMusic ? "Music in recording: On" : "Music in recording: Off");
                        savePrefs();
                    } else if (action == 1) {
                        JSONObject updated = new JSONObject();
                        updated.put("schemaVersion", 1);
                        updated.put("script", scriptEdit.getText().toString());
                        updated.put("size", teleprompterTextSize);
                        updated.put("ar", AppState.arFilter);
                        updated.put("music", musicTrackIndex);
                        updated.put("musicEnabled", useMusic);
                        updated.put("shape", selfieFrameShape);
                        updated.put("speed", AppState.speed);
                        prefs.edit().putString(key, updated.toString()).apply();
                        Toast.makeText(this, "Template updated: " + selected, Toast.LENGTH_SHORT).show();
                    } else if (action == 2) {
                        final EditText name = new EditText(this);
                        name.setSingleLine(true);
                        name.setHint("New template name");
                        new AlertDialog.Builder(this).setTitle("Duplicate " + selected).setView(name)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Duplicate", (dd, ww) -> {
                                    String newName = name.getText().toString().trim();
                                    if (newName.isEmpty() || "Default".equalsIgnoreCase(newName) ||
                                            prefs.contains("template:" + newName)) {
                                        Toast.makeText(this, "Enter a unique template name.", Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    prefs.edit().putString("template:" + newName, stored.toString()).apply();
                                    Toast.makeText(this, "Template duplicated: " + newName, Toast.LENGTH_SHORT).show();
                                }).show();
                    } else if (action == 3) {
                        new AlertDialog.Builder(this).setTitle("Delete " + selected + "?")
                                .setMessage("This cannot be undone.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete", (dd, ww) -> {
                                    prefs.edit().remove(key).apply();
                                    Toast.makeText(this, "Template deleted.", Toast.LENGTH_SHORT).show();
                                }).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Template action failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Cancel", null).show();
    }
    private void showVideoReview(final File clip) {
        if (clip == null || !clip.exists() || clip.length() == 0) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 12, 20, 12);
        TextView details = new TextView(this);
        details.setText(clip.getName() + " · " + Math.max(1, clip.length() / 1024) + " KB");
        details.setTextSize(13);
        panel.addView(details);
        final android.widget.VideoView player = new android.widget.VideoView(this);
        player.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300));
        panel.addView(player);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Review video").setView(panel)
                .setNegativeButton("Close", null)
                .setNeutralButton("Retake", null)
                .setPositiveButton("Export to phone", null).create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        dialog.dismiss();
                        startRecording();
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        dialog.dismiss();
                        exportVideoToPhone(clip);
                    }
                });
            }
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button discard = UiStyle.dark(this, "Discard video");
        discard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Discard this video?")
                        .setMessage("This permanently deletes " + clip.getName() + ".")
                        .setNegativeButton("Keep video", null)
                        .setPositiveButton("Discard", (dd, ww) -> {
                            if (clip.delete()) {
                                if (clip.getAbsolutePath().equals(AppState.lastFile)) AppState.lastFile = "";
                                dialog.dismiss();
                                Toast.makeText(MainActivity.this, "Video discarded.", Toast.LENGTH_SHORT).show();
                            } else Toast.makeText(MainActivity.this, "Could not delete this video.", Toast.LENGTH_LONG).show();
                        }).show();
            }
        });
        actions.addView(discard);
        panel.addView(actions);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                try { player.stopPlayback(); } catch (Exception ignored) {}
            }
        });
        dialog.show();
        try {
            Uri uri = ClipProvider.uriForFile(clip);
            player.setVideoURI(uri);
            player.setMediaController(new android.widget.MediaController(this));
            player.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                public void onPrepared(android.media.MediaPlayer mp) { mp.setLooping(false); player.start(); }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Could not load video preview: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void exportVideoToPhone(File clip) {
        if(clip==null || !clip.exists() || clip.length()==0) return;
        if(android.os.Build.VERSION.SDK_INT<29) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingExportClip = clip;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
                Toast.makeText(this, "Allow storage access to export the video.", Toast.LENGTH_LONG).show();
                return;
            }
            exportLegacyVideoToPhone(clip);
            return;
        }
        Uri dest=null;
        try {
            ContentValues v=new ContentValues(); v.put(MediaStore.Video.Media.DISPLAY_NAME,clip.getName());
            v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/TalkReelAR");
            v.put(MediaStore.Video.Media.IS_PENDING,1);
            dest=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
            if(dest==null) throw new IllegalStateException("Could not create destination");
            java.io.InputStream in=new java.io.FileInputStream(clip);
            java.io.OutputStream out=getContentResolver().openOutputStream(dest);
            if(out==null) throw new IllegalStateException("Could not open destination");
            byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n);
            in.close(); out.close(); ContentValues done=new ContentValues(); done.put(MediaStore.Video.Media.IS_PENDING,0);
            getContentResolver().update(dest,done,null,null);
            Toast.makeText(this,"Exported to Movies/TalkReelAR.",Toast.LENGTH_LONG).show();
        } catch(Exception e) {
            if(dest!=null) try { getContentResolver().delete(dest,null,null); } catch(Exception ignored) {}
            Toast.makeText(this,"Export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void exportLegacyVideoToPhone(File clip) {
        File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File dir = new File(movies, "TalkReelAR");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "Could not create Movies/TalkReelAR.", Toast.LENGTH_LONG).show();
            return;
        }
        File dest = new File(dir, clip.getName());
        try {
            java.io.InputStream in = new java.io.FileInputStream(clip);
            java.io.OutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close(); out.close();
            android.media.MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            Toast.makeText(this, "Exported to Movies/TalkReelAR.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void savePrefs() {
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        prefs.edit().putString("script", AppState.script).putFloat("speed", AppState.speed).putInt("langMode", AppState.langMode).putBoolean("showFrame", AppState.showFrame).putBoolean("isPro", AppState.isPro).putInt("textColor", AppState.textColorIndex).putInt("captionColor", AppState.captionColorIndex).putInt("musicTrack", musicTrackIndex).putBoolean("useMusic", useMusic).putInt("scriptEditorHeight", scriptEditorHeight).putBoolean("faceOn", preferFrontCamera).putInt("arFilter", AppState.arFilter).putInt("teleprompterTextSize", teleprompterTextSize).putInt("selfieFrameShape", selfieFrameShape).apply();
    }

    private void releaseCamera() {
        cameraReleasedForSocial = true;
        if (cameraHandler == null) {
            try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
            if (arCompositor != null) { try { arCompositor.stop(); } catch (Exception ignored) {} arCompositor = null; }
            try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
            cameraDevice = null;
            cameraOpening = false;
            return;
        }
        cameraHandler.post(new Runnable() {
            public void run() {
                try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
                captureSession = null;
                if (faceImageReader != null) { try { faceImageReader.close(); } catch (Exception ignored) {} faceImageReader = null; }
                if (frameView != null) frameView.clearFaces();
                if (arCompositor != null) {
                    try { arCompositor.stop(); } catch (Exception ignored) {}
                    arCompositor = null;
                }
                try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
                cameraDevice = null;
                cameraOpening = false;
            }
        });
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
        // Keep the camera session open: TalkReel's camera-first policy is
        // first-come-first-served. If another app already owns it, we wait.
        Intent service = new Intent(this, TalkReelService.class);
        service.setAction(TalkReelService.ACTION_START_OVERLAY);
        try {
            startForegroundService(service);
            Toast.makeText(this, "Overlay on. Open Instagram or YouTube.", Toast.LENGTH_LONG).show();
            statusView.setText("Overlay on · TalkReel keeps camera access if acquired first; otherwise it waits.");
        } catch (Exception e) {
            Toast.makeText(this, "Overlay failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openSocialApp(String pkg, String appName) {
        AppState.script = scriptEdit.getText().toString();
        savePrefs();
        // Do not voluntarily release the camera when launching a social app.
        // The app that acquired it first keeps ownership; TalkReel waits if busy.
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
        File mediaBase = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File dir = new File(mediaBase == null ? getFilesDir() : mediaBase, "TalkReelAR");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, "No videos yet. Press Live to record your first video.", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        final File[] clips = files;
        String[] labels = new String[clips.length];
        for (int i=0;i<clips.length;i++) {
            labels[i] = clips[i].getName() + " · " + Math.max(1, clips[i].length()/1024) + " KB";
        }
        new AlertDialog.Builder(this).setTitle("Review videos").setItems(labels,(d,which)->showVideoReview(clips[which]))
            .setNegativeButton("Close",null).show();
    }

    private void shareLastClip() {
        if (AppState.lastFile == null || AppState.lastFile.isEmpty()) {
            File mediaBase = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            File dir = new File(mediaBase == null ? getFilesDir() : mediaBase, "TalkReelAR");
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
        if (!AppState.isRecording || mediaRecorder == null) return;
        boolean pause = !AppState.isPaused;
        try {
            if (pause) mediaRecorder.pause();
            else mediaRecorder.resume();
            AppState.isPaused = pause;
            pauseButton.setText(pause ? "Resume" : "Pause");
            statusView.setText(pause ? "Paused" : "Recording resumed");
            if (pause) mainHandler.removeCallbacks(ticker);
            else mainHandler.post(ticker);
        } catch (Exception e) {
            Toast.makeText(this, "Could not " + (pause ? "pause" : "resume") + " recording: " +
                    (e.getMessage() == null ? "Recorder error." : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Recording starts only from the explicit Live button or Stop control.
        // Hardware volume keys retain their normal Android behavior.
        return super.onKeyDown(keyCode, event);
    }

    private Rect activeArray = null;
    private boolean isFrontCamera = true;
    // Face geometry comes from ML Kit contours on the YUV analysis stream.
    // Camera2 STATISTICS_FACES rectangles are deliberately not used as AR landmarks.
    private CameraCaptureSession.CaptureCallback faceCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult result) {
            // Capture metadata callback retained for camera request lifecycle.
        }
    };

    private void configureFaceAnalysis(CameraCharacteristics chars) {
        try {
            if (faceImageReader != null) { faceImageReader.close(); faceImageReader = null; }
            android.hardware.camera2.params.StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            android.util.Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) return;
            android.util.Size best = null;
            double score = Double.MAX_VALUE;
            float target = cameraBufferWidth / (float) cameraBufferHeight;
            for (android.util.Size size : sizes) {
                if (size.getWidth() < 320 || size.getHeight() < 240) continue;
                if (size.getWidth() > 1280 || size.getHeight() > 1280) continue;
                double ratioError = Math.abs(size.getWidth() / (double) size.getHeight() - target);
                double areaError = Math.abs(size.getWidth() * (double) size.getHeight() - 640d * 480d) / (640d * 480d);
                double candidate = ratioError * 100 + areaError;
                if (candidate < score) { score = candidate; best = size; }
            }
            if (best == null) best = sizes[0];
            faceImageReader = ImageReader.newInstance(best.getWidth(), best.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            if (faceTrackingEngine == null) {
                faceTrackingEngine = new FaceTrackingEngine(getApplicationContext(),
                        new FaceTrackingEngine.Listener() {
                    @Override public void onFaces(final List<com.google.mlkit.vision.face.Face> faces,
                                                  final int width, final int height) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (frameView != null && !activityDestroyed)
                                    int uprightWidth = (faceInputRotationDegrees % 180 == 90) ? height : width;
                                int uprightHeight = (faceInputRotationDegrees % 180 == 90) ? width : height;
                                frameView.setFaceResults(faces, uprightWidth, uprightHeight, 0, isFrontCamera);
                                if (coachView != null && !activityDestroyed)
                                    coachView.setText(faces.isEmpty()
                                            ? "Face not found · Move closer and face the camera"
                                            : "Face tracking · " + faces.size() + (faces.size()==1?" face":" faces"));
                            }
                        });
                    }
                });
            }
            faceImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try { image = reader.acquireLatestImage(); } catch (Exception ignored) {}
                    if (image != null && faceTrackingEngine != null)
                        faceTrackingEngine.process(image, faceInputRotationDegrees);
                    else if (image != null) image.close();
                }
            }, cameraHandler);
        } catch (Exception error) {
            if (faceImageReader != null) { try { faceImageReader.close(); } catch (Exception ignored) {} faceImageReader = null; }
        }
    }

    private void showCameraWaiting() {
        waitingForCamera = true;
        mainHandler.post(new Runnable() {
            public void run() {
                if (statusView != null) statusView.setText("Camera busy · Waiting for the other app to release it");
                if (!waitingToastShown) {
                    waitingToastShown = true;
                    Toast.makeText(MainActivity.this, "Camera in use by another app. TalkReel is waiting.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void openCamera() {
        // Guard: one open at a time. This stops the open/close loop.
        if (activityDestroyed || !activityResumed || cameraOpening || cameraDevice != null) return;
        if (textureView == null || !textureView.isAvailable()) return;
        cameraOpening = true;
        cameraHandler.post(new Runnable() {
            public void run() {
                CameraManager manager = cameraManager != null ? cameraManager : (CameraManager) getSystemService(CAMERA_SERVICE);
                try {
                    String cameraId = null;
                    for (String id : manager.getCameraIdList()) {
                        CameraCharacteristics c = manager.getCameraCharacteristics(id);
                        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == (preferFrontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) { cameraId = id; break; }
                    }
                    if (cameraId == null) cameraId = manager.getCameraIdList()[0];
                    selectedCameraId = cameraId;
                    synchronized (unavailableCameraIds) {
                        if (unavailableCameraIds.contains(cameraId)) {
                            cameraOpening = false;
                            showCameraWaiting();
                            return;
                        }
                    }
                    CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                    activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
                    int[] faceModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                    if (faceModes != null) {
                        for (int mode : faceModes) if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) faceDetectMode = mode;
                        if (faceDetectMode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                            for (int mode : faceModes) if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) faceDetectMode = mode;
                        }
                    }
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    isFrontCamera = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                    Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                    int displayDegrees = displayRotation == Surface.ROTATION_90 ? 90
                            : displayRotation == Surface.ROTATION_180 ? 180
                            : displayRotation == Surface.ROTATION_270 ? 270 : 0;
                    int sensorDegrees = sensorOrientation == null ? 0 : sensorOrientation;
                    cameraRotationDegrees = isFrontCamera
                            ? (360 - ((sensorDegrees + displayDegrees) % 360)) % 360
                            : (sensorDegrees - displayDegrees + 360) % 360;
                    // ML Kit receives the sensor image, so do not apply the front-preview mirror inversion.
                    faceInputRotationDegrees = isFrontCamera
                            ? (sensorDegrees + displayDegrees) % 360
                            : (sensorDegrees - displayDegrees + 360) % 360;
                    android.hardware.camera2.params.StreamConfigurationMap streamMap =
                            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (streamMap != null) {
                        android.util.Size[] outputs = streamMap.getOutputSizes(SurfaceTexture.class);
                        if (outputs != null && outputs.length > 0) {
                            float targetRatio = (sensorDegrees % 180 == 90) ? (16f / 9f) : (9f / 16f);
                            android.util.Size best = null;
                            double bestScore = Double.MAX_VALUE;
                            for (android.util.Size size : outputs) {
                                int sw = size.getWidth(), sh = size.getHeight();
                                if (sw <= 0 || sh <= 0 || sw > 1920 || sh > 1920) continue;
                                float ratio = sw / (float) sh;
                                double ratioError = Math.abs(ratio - targetRatio);
                                double areaPenalty = 1.0 - Math.min(1.0, (sw * (double) sh) / (1280d * 720d));
                                double score = ratioError * 100.0 + areaPenalty;
                                if (score < bestScore) { bestScore = score; best = size; }
                            }
                            if (best == null) best = outputs[0];
                            cameraBufferWidth = best.getWidth();
                            cameraBufferHeight = best.getHeight();
                        }
                    }
                    configureFaceAnalysis(chars);
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { cameraOpening = false; return; }
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        public void onOpened(CameraDevice camera) {
                            cameraOpening = false;
                            cameraDevice = camera;
                            boolean wasWaiting = waitingForCamera;
                            waitingForCamera = false;
                            waitingToastShown = false;
                            if (wasWaiting) mainHandler.post(new Runnable() {
                                public void run() {
                                    if (statusView != null) statusView.setText("Camera ready · Preview only · Not recording");
                                    Toast.makeText(MainActivity.this, "Camera ready. TalkReel preview resumed.", Toast.LENGTH_SHORT).show();
                                }
                            });
                            startPreview();
                            if (pendingRecordWhenCameraReady) {
                                pendingRecordWhenCameraReady = false;
                                mainHandler.postDelayed(new Runnable() {
                                    public void run() { if (!AppState.isRecording) startRecording(); }
                                }, 600);
                            }
                        }
                        public void onDisconnected(CameraDevice camera) {
                            cameraOpening = false;
                            camera.close();
                            if (cameraDevice == camera) cameraDevice = null;
                            if (arCompositor != null) { try { arCompositor.stop(); } catch (Exception ignored) {} arCompositor = null; }
                        }
                        public void onError(CameraDevice camera, int error) {
                            cameraOpening = false;
                            camera.close();
                            if (cameraDevice == camera) cameraDevice = null;
                            if (arCompositor != null) { try { arCompositor.stop(); } catch (Exception ignored) {} arCompositor = null; }
                            if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) {
                                showCameraWaiting();
                            } else {
                                mainHandler.post(new Runnable() {
                                    public void run() {
                                        if (statusView != null) statusView.setText("Camera error · Check camera permission or device availability");
                                        Toast.makeText(MainActivity.this, "TalkReel could not open the camera.", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                    }, cameraHandler);
                } catch (CameraAccessException e) {
                    cameraOpening = false;
                    if (e.getReason() == CameraAccessException.CAMERA_IN_USE || e.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE) {
                        showCameraWaiting();
                    } else {
                        mainHandler.post(new Runnable() {
                            public void run() {
                                if (statusView != null) statusView.setText("Camera error · " + e.getReason());
                            }
                        });
                    }
                } catch (Exception e) {
                    cameraOpening = false;
                    mainHandler.post(new Runnable() {
                        public void run() { if (statusView != null) statusView.setText("Camera error"); }
                    });
                }
            }
        });
    }

    private void startPreview() {
        if (activityDestroyed || cameraHandler == null) return;
        if (Looper.myLooper() != cameraHandler.getLooper()) {
            cameraHandler.post(new Runnable() { public void run() { startPreview(); } });
            return;
        }
        try {
            if (cameraDevice == null || textureView == null) return;
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            if (arCompositor == null) arCompositor = new ArCompositor(cameraHandler);
            Surface cameraSurface = arCompositor.start(texture, cameraBufferWidth, cameraBufferHeight);
            arCompositor.setFilter(AppState.arFilter % 6);
            arCompositor.setCameraOrientation(cameraRotationDegrees, isFrontCamera);
            if (captureSession != null) {
                try { captureSession.close(); } catch (Exception ignored) {}
                captureSession = null;
            }
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(cameraSurface);
            if (faceImageReader != null) previewBuilder.addTarget(faceImageReader.getSurface());
            previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
            List<Surface> previewOutputs = new ArrayList<Surface>();
            previewOutputs.add(cameraSurface);
            if (faceImageReader != null) previewOutputs.add(faceImageReader.getSurface());
            cameraDevice.createCaptureSession(previewOutputs, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        if (cameraDevice != null && !activityDestroyed)
                            session.setRepeatingRequest(previewBuilder.build(), faceCallback, cameraHandler);
                    } catch (Exception e) {
                        mainHandler.post(new Runnable() { public void run() {
                            if (statusView != null) statusView.setText("Camera preview could not start.");
                        }});
                    }
                }
                public void onConfigureFailed(CameraCaptureSession session) {
                    try { session.close(); } catch (Exception ignored) {}
                    if (faceImageReader != null) {
                        try { faceImageReader.close(); } catch (Exception ignored) {}
                        faceImageReader = null;
                        if (arCompositor != null) {
                            try { arCompositor.stop(); } catch (Exception ignored) {}
                            arCompositor = null;
                        }
                        if (frameView != null) frameView.clearFaces();
                        mainHandler.post(new Runnable() {
                            public void run() {
                                if (coachView != null) coachView.setText("Face contour analysis is not supported by this camera stream.");
                                if (statusView != null) statusView.setText("Camera preview active · face tracking unavailable on this device");
                            }
                        });
                        startPreview();
                    } else {
                        mainHandler.post(new Runnable() { public void run() {
                            if (statusView != null) statusView.setText("Camera preview configuration failed.");
                        }});
                    }
                }
            }, cameraHandler);
        } catch (Exception e) {
            final String message = e.getMessage();
            mainHandler.post(new Runnable() { public void run() {
                if (statusView != null) statusView.setText("Preview error: " + (message == null ? "camera initialization failed" : message));
            }});
        }
    }

    private boolean isMedia3ExportAvailable() {
        try {
            Class.forName("com.talkreel.ar.Media3ExportPipeline");
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void startRecording() {
        if (AppState.isRecording || pendingRecordWhenCameraReady) return;
        if (useMusic && !isMedia3ExportAvailable()) {
            Toast.makeText(this, "Digital music mixing requires the Media3-enabled Gradle build. Turn Music off or install that build.", Toast.LENGTH_LONG).show();
            return;
        }
        if (useMusic && (musicTrackIndex < 0 || musicTrackIndex >= MusicCatalog.TRACKS.length
                || !MusicEngine.isDownloaded(this, MusicCatalog.TRACKS[musicTrackIndex]))) {
            Toast.makeText(this, "Download the selected music track before recording to mix it cleanly into the finished MP4.", Toast.LENGTH_LONG).show();
            return;
        }
        MusicEngine.stop();
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 43);
            Toast.makeText(this, "Microphone permission is required to record video with audio.", Toast.LENGTH_LONG).show();
            return;
        }
        if (cameraDevice == null) {
            pendingRecordWhenCameraReady = true;
            statusView.setText("Waiting for camera · Recording will start when it is ready");
            openCamera();
            if (waitingForCamera) showCameraWaiting();
            return;
        }
        pendingRecordWhenCameraReady = true;
        AppState.script = scriptEdit.getText().toString();
        savePrefs();
        teleprompterView.setText(AppState.script);
        teleprompterView.setTextColor(AppState.textColor());
        CaptionStore.reset();
        SilenceLog.reset();
        quietSeconds = 0;

        cameraHandler.post(new Runnable() {
            public void run() {
                File output = null;
                try {
                    if (cameraDevice == null || textureView == null || !textureView.isAvailable())
                        throw new IllegalStateException("Camera preview is no longer available.");
                    if (arCompositor == null) startPreview();
                    if (arCompositor == null) throw new IllegalStateException("AR compositor is not ready.");
                    MediaRecorder recorder = new MediaRecorder();
                    mediaRecorder = recorder;
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setVideoSize(AppState.exportWidth(), AppState.exportHeight());
                    recorder.setVideoFrameRate(30);
                    File mediaBase = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    File dir = new File(mediaBase == null ? getFilesDir() : mediaBase, "TalkReelAR");
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create recording folder.");
                    String name = "TalkReel-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date()) + ".mp4";
                    output = new File(dir, name);
                    recorder.setOutputFile(output.getAbsolutePath());
                    recorder.prepare();
                    final File savedOutput = output;
                    final Surface recorderSurface = recorder.getSurface();
                    arCompositor.setFilter(AppState.arFilter % 6);
                    arCompositor.setEncoderSurface(recorderSurface);
                    final Surface cameraInput = arCompositor.getCameraInputSurface();
                    if (cameraInput == null) throw new IllegalStateException("AR camera input is unavailable.");
                    if (captureSession != null) {
                        try { captureSession.close(); } catch (Exception ignored) {}
                        captureSession = null;
                    }
                    previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    previewBuilder.addTarget(cameraInput);
                    if (faceImageReader != null) previewBuilder.addTarget(faceImageReader.getSurface());
                    previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
                    List<Surface> recordingOutputs = new ArrayList<Surface>();
                    recordingOutputs.add(cameraInput);
                    if (faceImageReader != null) recordingOutputs.add(faceImageReader.getSurface());
                    cameraDevice.createCaptureSession(recordingOutputs, new CameraCaptureSession.StateCallback() {
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                if (cameraDevice == null || activityDestroyed) throw new IllegalStateException("Camera closed before recording started.");
                                session.setRepeatingRequest(previewBuilder.build(), faceCallback, cameraHandler);
                                mediaRecorder.start();
                                AppState.lastFile = savedOutput.getAbsolutePath();
                                AppState.isRecording = true;
                                AppState.isPaused = false;
                                AppState.seconds = 0;
                                pendingRecordWhenCameraReady = false;
                                windowStartAt = System.currentTimeMillis();
                                wordsInWindow = 0;
                                Intent service = new Intent(MainActivity.this, TalkReelService.class);
                                service.setAction(TalkReelService.ACTION_START_SERVICE);
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                                    else startService(service);
                                } catch (Exception ignored) {}
                                mainHandler.post(new Runnable() {
                                    public void run() {
                                        if (recordButton != null) recordButton.setText("Stop recording");
                                        if (pauseButton != null) pauseButton.setText("Pause");
                                        if (statusView != null) statusView.setText("REC 0s  " + AppState.exportLabel());
                                        mainHandler.removeCallbacks(ticker);
                                        mainHandler.post(ticker);
                                    }
                                });
                            } catch (final Exception e) {
                                try { session.close(); } catch (Exception ignored) {}
                                captureSession = null;
                                failRecordingStart(savedOutput, e);
                            }
                        }
                        public void onConfigureFailed(CameraCaptureSession session) {
                            try { session.close(); } catch (Exception ignored) {}
                            failRecordingStart(savedOutput, new IllegalStateException("Camera could not configure the AR recording session."));
                        }
                    }, cameraHandler);
                } catch (Exception e) {
                    failRecordingStart(output, e);
                }
            }
        });
    }

    private void failRecordingStart(File output, final Exception error) {
        pendingRecordWhenCameraReady = false;
        AppState.isRecording = false;
        AppState.isPaused = false;
        MusicEngine.stop();
        if (arCompositor != null) { try { arCompositor.setEncoderSurface(null); } catch (Exception ignored) {} }
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        if (output != null && output.exists()) output.delete();
        mainHandler.post(new Runnable() {
            public void run() {
                if (recordButton != null) recordButton.setText("Live · Start recording");
                if (pauseButton != null) pauseButton.setText("Pause");
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (statusView != null) statusView.setText("Preview — not recording");
                Toast.makeText(MainActivity.this, "Recording did not start: " + detail, Toast.LENGTH_LONG).show();
                startPreview();
            }
        });
    }


    private void startMedia3Export(final File clip, final File music, final File srt) {
        final Runnable showFinal = new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                File finalClip = AppState.lastFile == null ? null : new File(AppState.lastFile);
                showVideoReview(finalClip != null && finalClip.isFile() && finalClip.length() > 0 ? finalClip : clip);
            }
        };
        final Runnable showOriginal = new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (statusView != null) statusView.setText("Original recording saved. Finished export was unavailable; review the original clip.");
                showVideoReview(clip);
            }
        };
        try {
            Class<?> pipeline = Class.forName("com.talkreel.ar.Media3ExportPipeline");
            java.lang.reflect.Method export = pipeline.getMethod("export",
                    android.app.Activity.class, File.class, File.class, File.class,
                    Handler.class, Runnable.class, Runnable.class);
            Object started = export.invoke(null, this, clip, music, srt, mainHandler, showFinal, showOriginal);
            if (Boolean.FALSE.equals(started)) showOriginal.run();
            else if (statusView != null) statusView.setText("Creating finished MP4 with selected music and captions…");
        } catch (Exception unavailable) {
            if (statusView != null) statusView.setText("Original recording saved. Advanced export is unavailable in this build.");
            showVideoReview(clip);
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
        AppState.isRecording = false;
        AppState.isPaused = false;
        Intent serviceFinished = new Intent(this, TalkReelService.class);
        serviceFinished.setAction(TalkReelService.ACTION_RECORDING_FINISHED);
        try { startService(serviceFinished); } catch (Exception ignored) {}
        cameraHandler.post(new Runnable() {
            public void run() {
                File clip = AppState.lastFile == null ? null : new File(AppState.lastFile);
                boolean finalized = false;
                String failure = null;
                try {
                    if (mediaRecorder == null) throw new IllegalStateException("Recorder is unavailable.");
                    mediaRecorder.stop();
                    finalized = true;
                } catch (Exception e) {
                    failure = e.getMessage() == null ? "The MP4 could not be finalized." : e.getMessage();
                } finally {
                    if (arCompositor != null) {
                        try { arCompositor.setEncoderSurface(null); } catch (Exception ignored) {}
                    }
                    if (mediaRecorder != null) {
                        try { mediaRecorder.reset(); } catch (Exception ignored) {}
                        try { mediaRecorder.release(); } catch (Exception ignored) {}
                        mediaRecorder = null;
                    }
                }
                if (captureSession != null) {
                    try { captureSession.close(); } catch (Exception ignored) {}
                    captureSession = null;
                }
                if (!finalized || clip == null || !clip.exists() || clip.length() == 0) {
                    if (clip != null && clip.exists()) clip.delete();
                    final String detail = failure == null ? "The output file is empty." : failure;
                    mainHandler.post(new Runnable() {
                        public void run() {
                            if (recordButton != null) recordButton.setText("Live · Start recording");
                            if (pauseButton != null) pauseButton.setText("Pause");
                            if (statusView != null) statusView.setText("Preview — not recording");
                            Toast.makeText(MainActivity.this, "Video could not be saved: " + detail + " Please record again.", Toast.LENGTH_LONG).show();
                            startPreview();
                        }
                    });
                    return;
                }
                CaptionStore.finish(finalSec);
                SilenceLog.finish(finalSec);
                String extra = "";
                final File musicForExport = useMusic && musicTrackIndex >= 0 && musicTrackIndex < MusicCatalog.TRACKS.length
                        ? MusicEngine.localFile(MainActivity.this, MusicCatalog.TRACKS[musicTrackIndex]) : null;
                final File captionsForExport;
                if (AppState.isPro) {
                    captionsForExport = CaptionStore.count() > 0 ? CaptionStore.write(clip) : null;
                    SilenceLog.write(clip);
                    extra = "  SRT: " + CaptionStore.count() + " lines. Cuts: " + SilenceLog.count() + ".";
                } else {
                    captionsForExport = null;
                }
                final String msg = extra;
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (recordButton != null) recordButton.setText("Live · Start recording");
                        if (pauseButton != null) pauseButton.setText("Pause");
                        if (statusView != null) statusView.setText("Saved in TalkReel app storage. Export from Review video." + msg);
                        Toast.makeText(MainActivity.this, "Recording saved. Review it, then export to your phone.", Toast.LENGTH_LONG).show();
                        startPreview();
                        if ((musicForExport != null && musicForExport.isFile() && musicForExport.length() > 0)
                                || (captionsForExport != null && captionsForExport.isFile())) {
                            startMedia3Export(clip, musicForExport, captionsForExport);
                        } else {
                            showVideoReview(clip);
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        super.onDestroy();
        try { unregisterReceiver(controlReceiver); } catch (Exception e) {}
        mainHandler.removeCallbacks(ticker);
        speechOn = false;
        stopCaptionListening();
        MusicEngine.stop();
        if (cameraManager != null && cameraAvailabilityCallback != null) {
            try { cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback); } catch (Exception e) {}
        }
        pendingRecordWhenCameraReady = false;
        if (AppState.isRecording) {
            Intent serviceFinished = new Intent(this, TalkReelService.class);
            serviceFinished.setAction(TalkReelService.ACTION_RECORDING_FINISHED);
            try { startService(serviceFinished); } catch (Exception ignored) {}
        }
        if (cameraHandler != null) {
            cameraHandler.post(new Runnable() {
                public void run() {
                    try {
                        if (mediaRecorder != null && AppState.isRecording) mediaRecorder.stop();
                    } catch (Exception ignored) {}
                    AppState.isRecording = false;
                    AppState.isPaused = false;
                    if (arCompositor != null) {
                        try { arCompositor.setEncoderSurface(null); } catch (Exception ignored) {}
                    }
                    if (mediaRecorder != null) {
                        try { mediaRecorder.reset(); } catch (Exception ignored) {}
                        try { mediaRecorder.release(); } catch (Exception ignored) {}
                        mediaRecorder = null;
                    }
                    try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
                    captureSession = null;
                    if (faceImageReader != null) { try { faceImageReader.close(); } catch (Exception ignored) {} faceImageReader = null; }
                    if (arCompositor != null) {
                        try { arCompositor.stop(); } catch (Exception ignored) {}
                        arCompositor = null;
                    }
                    try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
                    cameraDevice = null;
                }
            });
        }
        if (faceTrackingEngine != null) { try { faceTrackingEngine.close(); } catch (Exception ignored) {} faceTrackingEngine = null; }
        if (cameraThread != null) cameraThread.quitSafely();
    }
}
