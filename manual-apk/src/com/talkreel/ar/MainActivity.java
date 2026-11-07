package com.talkreel.ar;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.AlertDialog;
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
import android.util.Rational;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import androidx.core.content.ContextCompat;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import android.text.Editable;
import android.text.TextWatcher;
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
    private FrameLayout previewBox;
    private LinearLayout contentRoot;
    private SafeScrollView outerScroll;
    private int pipAspectMode = 0;
    private boolean floatingPreview = false;
    private int floatingPreviewWidthDp = 150;
    private float floatingPreviewX = -1f;
    private float floatingPreviewY = 88f;
    private int pipPreviousScrollY = 0;
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
    private Button customMusicButton;
    private Uri customMusicUri;
    private static final int REQUEST_CUSTOM_MUSIC = 701;
    private int scriptEditorHeight = 260;
    private boolean preferFrontCamera = true;
    private int musicTrackIndex = 0;
    private boolean useMusic = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface; // legacy reference retained for safe teardown\n    private volatile ArCompositor arCompositor;
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
        pipAspectMode = prefs.getInt("pipAspectMode", 0);
        floatingPreview = prefs.getBoolean("floatingPreview", false);
        floatingPreviewWidthDp = Math.max(120, Math.min(220, prefs.getInt("floatingPreviewWidthDp", 150)));
        floatingPreviewX = prefs.getFloat("floatingPreviewX", -1f);
        floatingPreviewY = prefs.getFloat("floatingPreviewY", 88f);
        String savedCustomMusic = prefs.getString("customMusicUri", "");
        if (savedCustomMusic != null && !savedCustomMusic.isEmpty()) {
            try { customMusicUri = Uri.parse(savedCustomMusic); } catch (Exception ignored) {}
        }
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
        ContextCompat.registerReceiver(this, controlReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        LinearLayout root = new LinearLayout(this);
        contentRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(16, 16, 16, 16);
        // Android 16 enforces edge-to-edge for apps targeting API 36.
        // Keep controls clear of status/navigation bars and display cutouts.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left, top, right, bottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(16 + left, 16 + top, 16 + right, 16 + bottom);
                return insets;
            }
        });

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

        previewBox = new FrameLayout(this);
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
        arNote.setText("Recorded Frame FX are rendered into the MP4. Face Studio is a separate 468-point ARCore preview; its mesh is not recorded yet.");
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
        teleprompterScroll.setBackground(UiStyle.panelBg(this));
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
        proButton = UiStyle.primary(this, AppState.isPro ? "Pro test: On" : "Pro test: Off");
        proButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.isPro = !AppState.isPro;
                proButton.setText(AppState.isPro ? "Pro test: On" : "Pro test: Off");
                savePrefs();
                coachView.setText(ScriptCoach.shortLine(scriptEdit.getText().toString()));
                statusView.setText(AppState.isPro ? "Pro preview enabled · no payment is processed · " + AppState.exportLabel() : "Free export · " + AppState.exportLabel());
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
        Button arStudioButton = UiStyle.primary(this, "Face Studio");
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

        LinearLayout pipRow = new LinearLayout(this);
        pipRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pipFrameButton = UiStyle.dark(this, "PiP frame: " + (pipAspectMode == 1 ? "1:1" : pipAspectMode == 2 ? "16:9" : "9:16"));
        pipFrameButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pipAspectMode = (pipAspectMode + 1) % 3;
                String label = pipAspectMode == 0 ? "9:16" : pipAspectMode == 1 ? "1:1" : "16:9";
                pipFrameButton.setText("PiP frame: " + label);
                updatePipParams();
                savePrefs();
            }
        });
        pipRow.addView(pipFrameButton);
        Button floatPreviewButton = UiStyle.primary(this, floatingPreview ? "Float: On" : "Float selfie");
        floatPreviewButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                floatingPreview = !floatingPreview;
                floatPreviewButton.setText(floatingPreview ? "Float: On" : "Float selfie");
                setFloatingPreview(floatingPreview);
                statusView.setText(floatingPreview ? "Floating selfie: drag the preview to move it; use Selfie size −/+ to resize." : "Selfie preview returned to the normal layout.");
                savePrefs();
            }
        });
        pipRow.addView(floatPreviewButton);
        Button enterPipButton = UiStyle.primary(this, "System PiP");
        enterPipButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enterSelfiePip(); }
        });
        pipRow.addView(enterPipButton);
        root.addView(pipRow);

        LinearLayout previewSizeRow = new LinearLayout(this);
        previewSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button previewSmaller = UiStyle.dark(this, "Selfie size −");
        previewSmaller.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resizeFloatingPreview(-20); }
        });
        previewSizeRow.addView(previewSmaller);
        Button previewLarger = UiStyle.dark(this, "Selfie size +");
        previewLarger.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resizeFloatingPreview(20); }
        });
        previewSizeRow.addView(previewLarger);
        root.addView(previewSizeRow);

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
        musicToggleButton = UiStyle.dark(this, useMusic ? "Music playback: On" : "Music playback: Off");
        musicToggleButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useMusic = !useMusic;
                musicToggleButton.setText(useMusic ? "Music playback: On" : "Music playback: Off");
                savePrefs();
                if (!useMusic) MusicEngine.stop();
                statusView.setText(useMusic ? "Speaker playback during recording: " + MusicCatalog.TRACKS[musicTrackIndex].title + ". Not digitally mixed." : "Music playback off. See Music below.");
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
        EditText musicSearch = new EditText(this);
        musicSearch.setSingleLine(true);
        musicSearch.setTextColor(0xFFFFFFFF);
        musicSearch.setHintTextColor(0xFF888888);
        musicSearch.setHint("Search music by title, artist or mood");
        musicSearch.setPadding(14, 10, 14, 10);
        root.addView(musicSearch);
        TextView volumeLabel = new TextView(this);
        volumeLabel.setText("Preview volume");
        volumeLabel.setTextColor(0xFFCCCCCC);
        volumeLabel.setTextSize(12);
        volumeLabel.setPadding(8, 6, 8, 0);
        root.addView(volumeLabel);
        SeekBar musicVolume = new SeekBar(this);
        musicVolume.setMax(100);
        musicVolume.setProgress(80);
        musicVolume.setContentDescription("Music preview volume");
        musicVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                MusicEngine.setVolume(progress / 100.0f);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(musicVolume);
        customMusicButton = UiStyle.primary(this, customMusicUri == null ? "Add audio from phone" : "Custom audio selected");
        customMusicButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickCustomMusic(); }
        });
        root.addView(customMusicButton);
        Button clearCustomMusicButton = UiStyle.dark(this, "Clear custom audio");
        clearCustomMusicButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                customMusicUri = null;
                customMusicButton.setText("Add audio from phone");
                savePrefs();
                musicStatusView.setText("Custom audio cleared. Choose a catalog track below.");
                MusicEngine.stop();
            }
        });
        root.addView(clearCustomMusicButton);
        LinearLayout musicList = new LinearLayout(this);
        musicList.setOrientation(LinearLayout.VERTICAL);
        SafeScrollView musicScroll = new SafeScrollView(this);
        
        // Avoid Android framework crashes when the themed scrollbar drawable is null.
        // Scrolling remains enabled; only scrollbar rendering is disabled.
        musicScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 320));
        musicScroll.setVerticalScrollBarEnabled(false);
        musicScroll.setBackground(UiStyle.panelBg(this));
        musicScroll.addView(musicList);
        root.addView(musicScroll);
        for (int ti = 0; ti < MusicCatalog.TRACKS.length; ti++) {
            final MusicCatalog.Track track = MusicCatalog.TRACKS[ti];
            final int trackIndex = ti;
            TextView trackView = new TextView(this);
            String lic = track.license;
            if (track.credit != null && !track.credit.isEmpty()) lic = lic + ". Credit: " + track.credit;
            trackView.setText(track.title + " - " + track.artist + "\n" + track.mood + ", " + track.useFor + ", " + track.duration + "\n" + lic);
            LinearLayout trackCard = new LinearLayout(this);
            trackCard.setOrientation(LinearLayout.VERTICAL);
            trackCard.setTag((track.title + " " + track.artist + " " + track.mood + " " + track.useFor).toLowerCase(Locale.US));
            trackView.setTextColor(0xFFFFFFFF);
            trackView.setTextSize(14);
            trackView.setPadding(8, 8, 8, 4);
            trackCard.addView(trackView);
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
            trackCard.addView(musicRow);
            musicList.addView(trackCard);
        }
        musicSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                for (int i = 0; i < musicList.getChildCount(); i++) {
                    View card = musicList.getChildAt(i);
                    Object tag = card.getTag();
                    card.setVisibility(query.isEmpty() || (tag != null && tag.toString().contains(query)) ? View.VISIBLE : View.GONE);
                }
            }
            public void afterTextChanged(Editable s) {}
        });
        TextView musicNote = new TextView(this);
        musicNote.setText("Sources: Pixabay Music, free, no attribution, and Kevin MacLeod, CC BY 4.0, credit required. See docs/MUSIC.md for every track, license, and link. Music plays through the speaker while you record, it is not mixed into the video file yet.");
        musicNote.setTextColor(0xFFAAAAAA);
        musicNote.setTextSize(12);
        musicNote.setPadding(8, 8, 8, 8);
        root.addView(musicNote);

        outerScroll = new SafeScrollView(this);
        outerScroll.setVerticalScrollBarEnabled(false);
        
        // Avoid Android framework crashes when the themed scrollbar drawable is null.
        // Scrolling remains enabled; only scrollbar rendering is disabled.
        outerScroll.addView(root);
        setContentView(outerScroll);
        outerScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (floatingPreview) updateFloatingPreviewPosition();
            }
        });
        if (floatingPreview) previewBox.post(new Runnable() { public void run() { setFloatingPreview(true); } });

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
                if (cameraHandler != null) cameraHandler.post(new Runnable() {
                    public void run() {
                        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                        try { if (arCompositor != null) { arCompositor.stop(); arCompositor = null; } } catch (Exception ignored) {}
                        try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) {}
                    }
                });
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    private void pickCustomMusic() {
        if (AppState.isRecording) {
            Toast.makeText(this, "Stop recording before changing the music source.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("audio/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, REQUEST_CUSTOM_MUSIC); }
        catch (Exception e) { Toast.makeText(this, "No audio file picker is available.", Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_MUSIC || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(selected, flags);
        } catch (Exception ignored) {}
        customMusicUri = selected;
        useMusic = true;
        if (musicToggleButton != null) musicToggleButton.setText("Music: On");
        if (customMusicButton != null) customMusicButton.setText("Custom audio selected");
        savePrefs();
        musicStatusView.setText("Selected your audio file. Previewing now; it will loop during the next recording.");
        MusicEngine.playUri(this, customMusicUri, false, mainHandler, new MusicEngine.Callback() {
            public void onResult(boolean ok, String message) { musicStatusView.setText(message); }
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
        if (requestCode == 3) {
            boolean micOk = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (micOk) {
                speechOn = true;
                restartCount = 0;
                windowStartAt = System.currentTimeMillis();
                wordsInWindow = 0;
                currentCaptionLang = AppState.langCode();
                startCaptionListening();
                statusView.setText("Captions on, " + AppState.langName());
            } else {
                statusView.setText("Microphone permission denied. Live captions are off.");
                captionView.setText("Allow microphone access to use live captions.");
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
        activityResumed = true;
        if (speechOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCaptionListening();
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

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setFloatingPreview(boolean enabled) {
        if (previewBox == null) return;
        floatingPreview = enabled;
        ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
        if (enabled) {
            lp.width = dp(floatingPreviewWidthDp);
            lp.height = Math.round(dp(floatingPreviewWidthDp) * (16.0f / 9.0f));
            previewBox.setElevation(dp(16));
            previewBox.setBackgroundColor(0xFF101010);
            previewBox.setOnTouchListener(new View.OnTouchListener() {
                private float startRawX, startRawY, startX, startY;
                public boolean onTouch(View view, MotionEvent event) {
                    if (!floatingPreview) return false;
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        startRawX = event.getRawX(); startRawY = event.getRawY();
                        startX = floatingPreviewX; startY = floatingPreviewY;
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        floatingPreviewX = startX + event.getRawX() - startRawX;
                        floatingPreviewY = startY + event.getRawY() - startRawY;
                        updateFloatingPreviewPosition();
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        updateFloatingPreviewPosition(); savePrefs(); return true;
                    }
                    return true;
                }
            });
            previewBox.post(new Runnable() { public void run() { updateFloatingPreviewPosition(); } });
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = 520;
            previewBox.setElevation(0f);
            previewBox.setTranslationX(0f);
            previewBox.setTranslationY(0f);
            previewBox.setOnTouchListener(null);
        }
        previewBox.setLayoutParams(lp);
    }

    private void updateFloatingPreviewPosition() {
        if (!floatingPreview || previewBox == null || outerScroll == null) return;
        int width = dp(floatingPreviewWidthDp);
        int height = Math.round(width * (16.0f / 9.0f));
        int viewportWidth = outerScroll.getWidth() > 0 ? outerScroll.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int viewportHeight = outerScroll.getHeight() > 0 ? outerScroll.getHeight() : getResources().getDisplayMetrics().heightPixels;
        if (floatingPreviewX < 0f) floatingPreviewX = viewportWidth - width - dp(12);
        floatingPreviewX = Math.max(dp(8), Math.min(floatingPreviewX, viewportWidth - width - dp(8)));
        floatingPreviewY = Math.max(dp(8), Math.min(floatingPreviewY, viewportHeight - height - dp(8)));
        previewBox.setTranslationX(floatingPreviewX - previewBox.getLeft());
        previewBox.setTranslationY(outerScroll.getScrollY() + floatingPreviewY - previewBox.getTop());
    }

    private void resizeFloatingPreview(int deltaDp) {
        if (!floatingPreview) {
            Toast.makeText(this, "Turn on Float selfie before resizing the preview.", Toast.LENGTH_SHORT).show();
            return;
        }
        floatingPreviewWidthDp = Math.max(120, Math.min(220, floatingPreviewWidthDp + deltaDp));
        setFloatingPreview(true);
        savePrefs();
    }

    private void updatePipParams() {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            Rational ratio = pipAspectMode == 1 ? new Rational(1, 1) : pipAspectMode == 2 ? new Rational(16, 9) : new Rational(9, 16);
            setPictureInPictureParams(new PictureInPictureParams.Builder().setAspectRatio(ratio).build());
        } catch (Exception ignored) {}
    }

    private void enterSelfiePip() {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Picture-in-picture requires Android 8.0 or newer.", Toast.LENGTH_LONG).show();
            return;
        }
        if (cameraDevice == null || textureView == null || !textureView.isAvailable()) {
            Toast.makeText(this, "Wait for the selfie camera preview before entering PiP.", Toast.LENGTH_LONG).show();
            return;
        }
        if (outerScroll != null) pipPreviousScrollY = outerScroll.getScrollY();
        updatePipParams();
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectMode == 1 ? new Rational(1, 1) : pipAspectMode == 2 ? new Rational(16, 9) : new Rational(9, 16))
                .build());
        } catch (Exception e) {
            Toast.makeText(this, "Could not enter PiP: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration);
        if (contentRoot == null || previewBox == null) return;
        for (int i = 0; i < contentRoot.getChildCount(); i++) {
            View child = contentRoot.getChildAt(i);
            child.setVisibility(!inPip || child == previewBox ? View.VISIBLE : View.GONE);
        }
        ViewGroup.LayoutParams lp = previewBox.getLayoutParams();
        if (inPip) {
            // Scroll the camera preview into the PiP viewport before hiding
            // the other controls; restore the editor position on exit.
            if (outerScroll != null) outerScroll.post(new Runnable() { public void run() { outerScroll.scrollTo(0, previewBox.getTop()); } });
            previewBox.setTranslationX(0f);
            previewBox.setTranslationY(0f);
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = 520;
        } else if (floatingPreview) {
            if (outerScroll != null) outerScroll.post(new Runnable() { public void run() { outerScroll.scrollTo(0, pipPreviousScrollY); } });
            lp.width = dp(floatingPreviewWidthDp);
            lp.height = Math.round(dp(floatingPreviewWidthDp) * (16.0f / 9.0f));
            previewBox.post(new Runnable() { public void run() { updateFloatingPreviewPosition(); } });
        } else {
            if (outerScroll != null) outerScroll.post(new Runnable() { public void run() { outerScroll.scrollTo(0, pipPreviousScrollY); } });
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = 520;
            previewBox.setTranslationX(0f);
            previewBox.setTranslationY(0f);
        }
        previewBox.setLayoutParams(lp);
    }

    private void savePrefs() {
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        prefs.edit().putString("customMusicUri", customMusicUri == null ? "" : customMusicUri.toString()).putString("script", AppState.script).putFloat("speed", AppState.speed).putInt("langMode", AppState.langMode).putBoolean("showFrame", AppState.showFrame).putBoolean("isPro", AppState.isPro).putInt("textColor", AppState.textColorIndex).putInt("captionColor", AppState.captionColorIndex).putInt("musicTrack", musicTrackIndex).putBoolean("useMusic", useMusic).putInt("scriptEditorHeight", scriptEditorHeight).putBoolean("faceOn", preferFrontCamera).putInt("arFilter", AppState.arFilter).putInt("pipAspectMode", pipAspectMode).putBoolean("floatingPreview", floatingPreview).putInt("floatingPreviewWidthDp", floatingPreviewWidthDp).putFloat("floatingPreviewX", floatingPreviewX).putFloat("floatingPreviewY", floatingPreviewY).apply();
    }

    private void releaseCamera() {
        cameraReleasedForSocial = true;
        if (cameraHandler == null) return;
        cameraHandler.post(new Runnable() {
            public void run() {
                try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                try { if (arCompositor != null) { arCompositor.stop(); arCompositor = null; } } catch (Exception ignored) {}
                try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) {}
                try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
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
        File[] files = AppStorage.listVideos(MainActivity.this);
        if (files == null || files.length == 0) {
            statusView.setText("No clips yet");
            Toast.makeText(this, "No clips yet. Record one first.", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        final File[] clips = files;
        String[] labels = new String[clips.length];
        for (int i = 0; i < clips.length; i++) {
            labels[i] = clips[i].getName() + "  ·  " + android.text.format.Formatter.formatShortFileSize(this, clips[i].length());
        }
        new AlertDialog.Builder(this)
            .setTitle("Your clips  ·  " + clips.length)
            .setItems(labels, (dialog, which) -> {
                File selected = clips[which];
                AppState.lastFile = selected.getAbsolutePath();
                try {
                    Uri uri = ClipProvider.uriForFile(selected);
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, "video/mp4");
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(view, "Play clip"));
                    statusView.setText("Opening " + selected.getName());
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No video player available: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void shareLastClip() {
        if (AppState.lastFile == null || AppState.lastFile.isEmpty()) {
            File dir = AppStorage.videoDir(MainActivity.this);
            File[] files = AppStorage.listVideos(MainActivity.this);
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
            statusView.setText("Microphone permission is needed for live captions.");
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
                    if (arCompositor != null && !rects.isEmpty()) {
                        Rect face = rects.get(0);
                        arCompositor.setFaceRect(face.left / (float) vw, face.top / (float) vh,
                                face.right / (float) vw, face.bottom / (float) vh, isFrontCamera);
                    }
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
        if (cameraHandler == null) return;
        if (Looper.myLooper() != cameraHandler.getLooper()) {
            cameraHandler.post(new Runnable() { public void run() { startPreview(); } });
            return;
        }
        try {
            if (cameraDevice == null || textureView == null || !textureView.isAvailable()) return;
            SurfaceTexture tex = textureView.getSurfaceTexture();
            if (tex == null) return;
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (arCompositor == null) arCompositor = new ArCompositor(cameraHandler);
            Surface cameraInput = arCompositor.start(tex, AppState.exportWidth(), AppState.exportHeight());
            arCompositor.setFilter(AppState.arFilter);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(cameraInput);
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
            cameraDevice.createCaptureSession(Arrays.asList(cameraInput), new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try { if (cameraDevice != null) session.setRepeatingRequest(previewBuilder.build(), faceCallback, cameraHandler); }
                    catch (Exception e) { postCameraStatus("Preview could not start: " + e.getMessage()); }
                }
                public void onConfigureFailed(CameraCaptureSession session) { postCameraStatus("Camera preview configuration failed."); }
            }, cameraHandler);
        } catch (final Exception e) {
            postCameraStatus("Preview error: " + (e.getMessage() == null ? "camera initialization failed" : e.getMessage()));
        }
    }

    private void postCameraStatus(final String message) {
        mainHandler.post(new Runnable() { public void run() { if (statusView != null) statusView.setText(message); } });
    }

    private int recordWaitCount = 0;
    private boolean recordingStarting = false;
    private boolean activityResumed = false;
    private void startRecording() {
        if (!activityResumed || AppState.isRecording || recordingStarting) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            statusView.setText("Microphone permission is needed to record.");
            return;
        }
        recordingStarting = true;
        statusView.setText("Preparing recording…");
        recordButton.setEnabled(false);
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
        Intent service = new Intent(this, TalkReelService.class);
        service.setAction(TalkReelService.ACTION_START_SERVICE);
        try { startForegroundService(service); } catch (Exception ignored) {}
        cameraHandler.post(new Runnable() {
            public void run() {
                try {
                    if (cameraDevice == null || textureView == null || !textureView.isAvailable())
                        throw new IllegalStateException("Camera preview is unavailable.");
                    if (arCompositor == null) {
                        SurfaceTexture previewTexture = textureView.getSurfaceTexture();
                        if (previewTexture == null) throw new IllegalStateException("Preview surface is unavailable.");
                        arCompositor = new ArCompositor(cameraHandler);
                        arCompositor.start(previewTexture, AppState.exportWidth(), AppState.exportHeight());
                        arCompositor.setFilter(AppState.arFilter);
                    }
                    mediaRecorder = new MediaRecorder();
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mediaRecorder.setVideoSize(AppState.exportWidth(), AppState.exportHeight());
                    mediaRecorder.setVideoFrameRate(30);
                    mediaRecorder.setVideoEncodingBitRate(AppState.exportWidth() * AppState.exportHeight() * 5);
                    File dir = AppStorage.videoDir(MainActivity.this);
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create video storage.");
                    String name = "TalkReel-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(new Date()) + ".mp4";
                    File out = new File(dir, name);
                    AppState.lastFile = out.getAbsolutePath();
                    mediaRecorder.setOutputFile(out.getAbsolutePath());
                    mediaRecorder.prepare();
                    Surface recorderSurface = mediaRecorder.getSurface();
                    arCompositor.setEncoderSurface(recorderSurface);
                    Surface cameraInput = arCompositor.getCameraInputSurface();
                    if (captureSession != null) { captureSession.close(); captureSession = null; }
                    previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    previewBuilder.addTarget(cameraInput);
                    previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    previewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                    previewBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
                    cameraDevice.createCaptureSession(Arrays.asList(cameraInput), new CameraCaptureSession.StateCallback() {
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                if (!activityResumed) throw new IllegalStateException("Recording cancelled because TalkReel left the foreground.");
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
                                        // Until the digital audio mixer lands, this legacy playback is
                                        // intentionally not described as embedded soundtrack audio.
                                        if (useMusic && customMusicUri != null) {
                                            MusicEngine.playUri(MainActivity.this, customMusicUri, true, mainHandler, null);
                                        } else if (useMusic && musicTrackIndex >= 0 && musicTrackIndex < MusicCatalog.TRACKS.length) {
                                            MusicEngine.play(MainActivity.this, MusicCatalog.TRACKS[musicTrackIndex], true, mainHandler, null);
                                        }
                                        recordButton.setText("Stop");
                                        recordButton.setEnabled(true);
                                        pauseButton.setEnabled(true);
                                        statusView.setText("REC 0s · GPU effects are included in the MP4");
                                        mainHandler.post(ticker);
                                    }
                                });
                            } catch (final Exception e) { failRecording(e); }
                        }
                        public void onConfigureFailed(CameraCaptureSession session) {
                            failRecording(new Exception("The camera could not configure the recording session."));
                        }
                    }, cameraHandler);
                } catch (final Exception e) { failRecording(e); }
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
                    try { if (arCompositor != null) arCompositor.setEncoderSurface(null); } catch (Exception ignored) {}
                    try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                    try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) {}
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
                            if (activityResumed && cameraDevice != null && textureView != null && textureView.isAvailable()) startPreview();
                            else if (!activityResumed) releaseCamera();
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
                try { if (arCompositor != null) arCompositor.setEncoderSurface(null); } catch (Exception ignored) {}
                try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) {}
                CaptionStore.finish(finalSec);
                SilenceLog.finish(finalSec);
                File clip = new File(AppState.lastFile);
                if (stopError != null) {
                    if (clip.exists()) clip.delete();
                    AppState.lastFile = "";
                }
                android.net.Uri galleryUri = stopError == null ? MediaLibrary.publishVideo(getContentResolver(), clip) : null;
                String extra = galleryUri != null ? " Also published to Movies/TalkReelAR." : "";
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
                            statusView.setText((galleryUri != null ? "Saved to Movies/TalkReelAR." : "Saved in TalkReel app storage.") + msg);
                            Toast.makeText(MainActivity.this, galleryUri != null ? "Clip saved to Movies/TalkReelAR." : "Clip saved in TalkReel. Use Clips to play or share it.", Toast.LENGTH_LONG).show();
                        } else {
                            statusView.setText("Recording was too short or could not be finalized.");
                            Toast.makeText(MainActivity.this, "The clip could not be saved. Try recording again.", Toast.LENGTH_LONG).show();
                        }
                        stopService(new Intent(MainActivity.this, TalkReelService.class));
                        if (activityResumed) startPreview(); else releaseCamera();
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (speechOn) stopCaptionListening();
        if (recordingStarting) failRecording(new IllegalStateException("Recording cancelled because TalkReel left the foreground."));
        else if (AppState.isRecording) stopRecording();
        else releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(ticker);
        speechOn = false;
        stopCaptionListening();
        MusicEngine.stop();
        try { unregisterReceiver(controlReceiver); } catch (Exception e) {}
        if (cameraHandler != null) cameraHandler.post(new Runnable() {
            public void run() {
                try {
                    if (mediaRecorder != null) {
                        try { mediaRecorder.stop(); } catch (Exception ignored) {}
                        mediaRecorder.release();
                        mediaRecorder = null;
                    }
                } catch (Exception ignored) {}
                try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
                try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) {}
                try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
            }
        });
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}
