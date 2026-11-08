package com.talkreel.ar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    private boolean overlayCollapsed = false;
    private LinearLayout overlayControls;
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
        boolean recording = AppState.isRecording;
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
        if (intent == null) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            stopOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_RECORD.equals(action)) {
            // Request only, MainActivity owns the result.
            sendBroadcast(new Intent(AppState.ACTION_RECORD));
            return START_NOT_STICKY;
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
            if (overlayView == null) {
                stopForeground(true);
                stopSelf();
            }
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
        return START_NOT_STICKY;
    }

    private void refreshOverlayButtons() {
        if (recButton != null) recButton.setText(overlayScrollRunning ? "Pause scroll" : "Start scroll");
        if (pauseButton != null) pauseButton.setText("Rewind");
        if (overlayTimer != null) overlayTimer.setText(overlayScrollRunning ? "Teleprompter scrolling" : "Teleprompter paused");
    }

    private void showOverlay() {
        if (overlayView != null) { refreshOverlayButtons(); return; }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is off. Allow TalkReel over other apps, then tap Overlay again.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception ignored) {}
            return;
        }
        SharedPreferences prefs = getSharedPreferences("talkreel", MODE_PRIVATE);
        AppState.script = prefs.getString("script", AppState.script);
        AppState.speed = prefs.getFloat("speed", 1.0f);
        AppState.textColorIndex = prefs.getInt("textColor", 0);
        overlayTextSize = prefs.getInt("teleprompterTextSize", 26);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        try { windowManager.getDefaultDisplay().getMetrics(dm); } catch (Exception ignored) {}
        final int screenW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        final int screenH = dm.heightPixels > 0 ? dm.heightPixels : 1920;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14, 10, 14, 12);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xE91A1D24);
        panel.setCornerRadius(24);
        panel.setStroke(1, 0x44FFFFFF);
        root.setBackground(panel);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand = new TextView(this);
        brand.setText("TALKREEL  /  TELEPROMPTER");
        brand.setTextColor(0xFFF4F5F7);
        brand.setTextSize(11);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        overlayTimer = new TextView(this);
        overlayTimer.setText("READY");
        overlayTimer.setTextColor(0xFFB9C0CA);
        overlayTimer.setTextSize(10);
        overlayTimer.setPadding(6, 0, 8, 0);
        header.addView(overlayTimer);

        final Button collapse = new Button(this);
        collapse.setText("−");
        collapse.setTextColor(0xFFFFFFFF);
        collapse.setTextSize(18);
        collapse.setMinWidth(42);
        collapse.setPadding(0, 0, 0, 0);
        collapse.setBackgroundColor(Color.TRANSPARENT);
        header.addView(collapse, new LinearLayout.LayoutParams(44, 42));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44));

        overlayControls = new LinearLayout(this);
        overlayControls.setOrientation(LinearLayout.VERTICAL);
        overlayControls.setPadding(0, 6, 0, 0);

        overlayScroll = new SafeScrollView(this);
        overlayScroll.setVerticalScrollBarEnabled(false);
        overlayScroll.setFillViewport(false);
        int scriptHeight = Math.max(104, Math.min(190, (int)(screenH * 0.14f)));
        overlayScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scriptHeight));
        overlayText = new TextView(this);
        overlayText.setText(AppState.script);
        overlayText.setTextColor(AppState.textColor());
        overlayText.setTextSize(overlayTextSize);
        overlayText.setLineSpacing(5, 1.22f);
        overlayText.setPadding(8, 8, 8, 8);
        overlayScroll.addView(overlayText);
        overlayControls.addView(overlayScroll);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        recButton = UiStyle.primary(this, "Start scroll");
        recButton.setOnClickListener(v -> {
            overlayScrollRunning = !overlayScrollRunning;
            refreshOverlayButtons();
        });
        transport.addView(recButton);
        pauseButton = UiStyle.dark(this, "Rewind");
        pauseButton.setOnClickListener(v -> {
            overlayScrollRunning = false;
            if (overlayScroll != null) overlayScroll.scrollTo(0, 0);
            refreshOverlayButtons();
        });
        transport.addView(pauseButton);
        overlayControls.addView(transport);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button smaller = UiStyle.dark(this, "A−");
        smaller.setOnClickListener(v -> {
            overlayTextSize = Math.max(16, overlayTextSize - 2);
            if (overlayText != null) overlayText.setTextSize(overlayTextSize);
            prefs.edit().putInt("teleprompterTextSize", overlayTextSize).apply();
        });
        tools.addView(smaller);
        Button larger = UiStyle.dark(this, "A+");
        larger.setOnClickListener(v -> {
            overlayTextSize = Math.min(48, overlayTextSize + 2);
            if (overlayText != null) overlayText.setTextSize(overlayTextSize);
            prefs.edit().putInt("teleprompterTextSize", overlayTextSize).apply();
        });
        tools.addView(larger);
        Button color = UiStyle.dark(this, "Text color");
        color.setOnClickListener(v -> {
            AppState.nextTextColor();
            prefs.edit().putInt("textColor", AppState.textColorIndex).apply();
            if (overlayText != null) overlayText.setTextColor(AppState.textColor());
        });
        tools.addView(color);
        Button music = UiStyle.dark(this, "Music");
        music.setOnClickListener(v -> {
            if (MusicEngine.isPlaying()) {
                MusicEngine.stop();
                music.setText("Music");
            } else {
                int idx = prefs.getInt("musicTrack", 0);
                if (idx < 0 || idx >= MusicCatalog.TRACKS.length) idx = 0;
                MusicEngine.play(TalkReelService.this, MusicCatalog.TRACKS[idx], true,
                        new Handler(Looper.getMainLooper()), null);
                music.setText("Music on");
            }
        });
        tools.addView(music);
        overlayControls.addView(tools);
        root.addView(overlayControls);

        collapse.setOnClickListener(v -> {
            overlayCollapsed = !overlayCollapsed;
            overlayControls.setVisibility(overlayCollapsed ? View.GONE : View.VISIBLE);
            collapse.setText(overlayCollapsed ? "+" : "−");
        });

        final WindowManager.LayoutParams params;
        int overlayType = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                Math.min((int)(screenW * 0.94f), screenW - 24),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.max(48, (int)(screenH * 0.08f));

        // Only the header drags the panel, so buttons and teleprompter scrolling remain usable.
        header.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = params.x; startY = params.y;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = startX + (int)(event.getRawX() - downX);
                    params.y = Math.max(0, startY + (int)(event.getRawY() - downY));
                    try { windowManager.updateViewLayout(root, params); } catch (Exception ignored) {}
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP;
            }
        });

        overlayView = root;
        overlayCollapsed = false;
        try {
            windowManager.addView(overlayView, params);
            Toast.makeText(this, "Teleprompter ready.", Toast.LENGTH_SHORT).show();
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
