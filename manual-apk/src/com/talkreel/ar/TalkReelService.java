package com.talkreel.ar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// v0.6 professional rebuild.
// Two separate jobs, never mixed:
// 1. Foreground service: keeps TalkReel alive while recording.
//    Started by ACTION_START_SERVICE. It shows no overlay.
// 2. Overlay: teleprompter only, shown only when the user taps
//    Overlay. Started by ACTION_START_OVERLAY.
// Overlay buttons only send a request by broadcast. They never
// change AppState. MainActivity owns recording, and this service
// polls AppState every 500ms to refresh its timer and buttons.
// That removes the v0.5 double-toggle open/close bug.
// No basic AR here. No circle view, no dim, no filters.
public class TalkReelService extends Service {
    public static final String ACTION_START_SERVICE = "com.talkreel.ar.START_SERVICE";
    public static final String ACTION_START_OVERLAY = "com.talkreel.ar.START_OVERLAY";
    public static final String ACTION_STOP_ALL = "com.talkreel.ar.STOP_ALL";
    public static final String ACTION_TOGGLE_RECORD = "com.talkreel.ar.TOGGLE_RECORD";
    public static final String ACTION_RECORDING_FINISHED = "com.talkreel.ar.RECORDING_FINISHED";

    private WindowManager windowManager;
    private View overlayView;
    private TextView overlayText;
    private TextView overlayTimer;
    private SafeScrollView overlayScroll;
    private Button recButton;
    private Button pauseButton;
    private int overlayTeleHeight = 260;
    private int overlayTextSize = 26;
    private boolean overlayScrollRunning = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker = new Runnable() {
        public void run() {
            refreshOverlayButtons();
            if (overlayScroll != null && overlayScrollRunning) {
                overlayScroll.scrollBy(0, (int) (6 * AppState.speed));
            }
            if (overlayView != null) handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private void startForegroundNotification(int foregroundType) {
        String channelId = "talkreel_activity";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId, "TalkReel activity", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when TalkReel recording or the floating teleprompter is active.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, channelId);
        else b = new Notification.Builder(this);
        boolean recording = (foregroundType & (android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) != 0;
        Notification n = b.setContentTitle("TalkReel AR")
            .setContentText(recording ? "Recording is active. Tap to return to TalkReel." : "Floating teleprompter is active. Tap to return.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, foregroundType);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            stopOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_RECORD.equals(action)) {
            // Request only, MainActivity owns the result.
            sendBroadcast(new Intent(AppState.ACTION_RECORD));
            return START_STICKY;
        }
        if (ACTION_RECORDING_FINISHED.equals(action)) {
            if (overlayView != null) {
                int type = android.os.Build.VERSION.SDK_INT >= 34
                        ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
                startForegroundNotification(type);
                return START_NOT_STICKY;
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_OVERLAY.equals(action)) {
            int type = android.os.Build.VERSION.SDK_INT >= 34
                    ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            if (AppState.isRecording && android.os.Build.VERSION.SDK_INT >= 34) {
                type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForegroundNotification(type);
            showOverlay();
            return START_NOT_STICKY;
        }
        // Recording uses camera and microphone only after the user starts a clip.
        int recordingType = android.os.Build.VERSION.SDK_INT >= 29
                ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        if (overlayView != null && android.os.Build.VERSION.SDK_INT >= 34) {
            recordingType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        startForegroundNotification(recordingType);
        return START_STICKY;
    }

    private void refreshOverlayButtons() {
        if (recButton != null) recButton.setText(overlayScrollRunning ? "Pause scroll" : "Start scroll");
        if (pauseButton != null) pauseButton.setText("Reset text");
        if (overlayTimer != null) overlayTimer.setText(overlayScrollRunning ? "Teleprompter scrolling" : "Teleprompter paused");
    }

    private void showOverlay() {
        if (overlayView != null) {
            refreshOverlayButtons();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is off. Allow TalkReel over other apps, then tap Overlay again.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception e) {}
            return;
        }
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        AppState.script = prefs.getString("script", AppState.script);
        AppState.speed = prefs.getFloat("speed", 1.0f);
        AppState.showFrame = prefs.getBoolean("showFrame", true);
        AppState.textColorIndex = prefs.getInt("textColor", 0);
        overlayTextSize = prefs.getInt("teleprompterTextSize", 26);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Toast.makeText(this, "Overlay failed: no WindowManager.", Toast.LENGTH_LONG).show();
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        try { windowManager.getDefaultDisplay().getMetrics(dm); } catch (Exception e) {}
        int screenW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        int screenH = dm.heightPixels > 0 ? dm.heightPixels : 1920;
        int teleH = Math.max(180, Math.min(340, (int) (screenH * 0.22)));
        overlayTeleHeight = teleH;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(UiStyle.panelBg());
        layout.setPadding(12, 12, 12, 12);

        overlayTimer = new TextView(this);
        overlayTimer.setText("Ready");
        overlayTimer.setTextColor(0xFFFFFFFF);
        overlayTimer.setTextSize(12);
        layout.addView(overlayTimer);
        TextView modeNote = new TextView(this);
        modeNote.setText("Camera access is first-come, first-served. If another app owns it, TalkReel waits and notifies you.");
        modeNote.setTextColor(0xFFCCCCCC);
        modeNote.setTextSize(11);
        modeNote.setPadding(2, 4, 2, 8);
        layout.addView(modeNote);

        overlayScroll = new SafeScrollView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (screenW * 0.74f), teleH);
        overlayScroll.setLayoutParams(sp);
        overlayScroll.setVerticalScrollBarEnabled(false);
        overlayText = new TextView(this);
        overlayText.setText(AppState.script);
        overlayText.setTextColor(AppState.textColor());
        overlayText.setTextSize(overlayTextSize);
        overlayText.setLineSpacing(8, 1.35f);
        overlayText.setPadding(8, 8, 8, 8);
        overlayScroll.addView(overlayText);
        layout.addView(overlayScroll);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        recButton = UiStyle.primary(this, "Start scroll");
        recButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                overlayScrollRunning = !overlayScrollRunning;
                refreshOverlayButtons();
            }
        });
        row.addView(recButton);
        pauseButton = UiStyle.dark(this, "Rewind");
        pauseButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                overlayScrollRunning = false;
                if (overlayScroll != null) overlayScroll.scrollTo(0, 0);
                refreshOverlayButtons();
            }
        });
        row.addView(pauseButton);
        layout.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        final Button textButton = UiStyle.dark(this, "Text color: " + AppState.textColorName());
        textButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppState.nextTextColor();
                getSharedPreferences("talkreel", MODE_PRIVATE).edit().putInt("textColor", AppState.textColorIndex).apply();
                textButton.setText("Text color: " + AppState.textColorName());
                if (overlayText != null) overlayText.setTextColor(AppState.textColor());
            }
        });
        row2.addView(textButton);
        Button smallerButton = UiStyle.dark(this, "Text size −");
        smallerButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                overlayTextSize = Math.max(16, overlayTextSize - 2);
                if (overlayText != null) overlayText.setTextSize(overlayTextSize);
                getSharedPreferences("talkreel", MODE_PRIVATE).edit().putInt("teleprompterTextSize", overlayTextSize).apply();
            }
        });
        row2.addView(smallerButton);
        Button largerButton = UiStyle.dark(this, "Text size +");
        largerButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                overlayTextSize = Math.min(48, overlayTextSize + 2);
                if (overlayText != null) overlayText.setTextSize(overlayTextSize);
                getSharedPreferences("talkreel", MODE_PRIVATE).edit().putInt("teleprompterTextSize", overlayTextSize).apply();
            }
        });
        row2.addView(largerButton);
        // v0.7.0: old Sound button played synthesized beeps. Now it plays
        // the track picked in the Music section, streamed if not downloaded.
        final Button musicButton = UiStyle.dark(this, "Preview music");
        musicButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (MusicEngine.isPlaying()) {
                    MusicEngine.stop();
                    musicButton.setText("Preview music");
                } else {
                    int idx = getSharedPreferences("talkreel", MODE_PRIVATE).getInt("musicTrack", 0);
                    if (idx < 0 || idx >= MusicCatalog.TRACKS.length) idx = 0;
                    MusicEngine.play(TalkReelService.this, MusicCatalog.TRACKS[idx], true, new android.os.Handler(android.os.Looper.getMainLooper()), null);
                    musicButton.setText("Music playing");
                }
            }
        });
        row2.addView(musicButton);
        Button close = UiStyle.dark(this, "Close overlay");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopOverlay(); }
        });
        row2.addView(close);
        layout.addView(row2);

        final WindowManager.LayoutParams params;
        if (Build.VERSION.SDK_INT >= 26) {
            params = new WindowManager.LayoutParams((int) (screenW * 0.78f), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        } else {
            params = new WindowManager.LayoutParams(screenW - 32, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        }
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 60;

        layout.setOnTouchListener(new View.OnTouchListener() {
            private int lastY = 0;
            private boolean dragging = false;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { lastY = (int) e.getRawY(); dragging = true; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE && dragging) {
                    int dy = (int) e.getRawY() - lastY;
                    params.y += dy;
                    lastY = (int) e.getRawY();
                    try { windowManager.updateViewLayout(overlayView, params); } catch (Exception ex) {}
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { dragging = false; return true; }
                return false;
            }
        });

        overlayView = layout;
        try {
            windowManager.addView(overlayView, params);
            Toast.makeText(this, "Overlay on. Open Instagram or YouTube.", Toast.LENGTH_SHORT).show();
            handler.post(ticker);
        } catch (Exception e) {
            overlayView = null;
            Toast.makeText(this, "Overlay failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshOverlayButtons();
    }

    private void stopOverlay() {
        overlayScrollRunning = false;
        handler.removeCallbacks(ticker);
        MusicEngine.stop();
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception e) {}
            overlayView = null;
        }
        if (!AppState.isRecording) {
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stopOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
