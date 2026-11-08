package com.talkreel.ar;

// v0.6 professional rebuild.
// One owner for recording state: MainActivity only.
// The overlay never changes this state itself, it only sends
// a request by broadcast. That was the v0.5 open/close bug:
// the overlay set isRecording and MainActivity set it again,
// so Record became Stop, and Stop did nothing.
// Basic AR is removed. No Beauty, Cartoon, Animal, Glasses,
// Glow, or Circle filter. Framing is a clean Pro guide only.
public class AppState {
    public static volatile boolean isRecording = false;
    public static volatile boolean isPaused = false;
    public static volatile int seconds = 0;
    public static volatile boolean showFrame = true;
    // Restrained camera color looks; no novelty face stickers.
    public static volatile int arFilter = ProFrameView.FILTER_GUIDE;
    public static volatile boolean faceFound = false;
    public static volatile float speed = 1.0f;
    public static volatile String script = "Hi, this is my honest review in 30 seconds.\n\nFirst, this is what I liked.\n\nSecond, this is what I did not like.\n\nMy verdict: yes, if you need it for daily use.";
    public static volatile String lastFile = "";
    public static volatile String lastSrtFile = "";
    public static volatile String lastCutFile = "";
    // Pro: paid tier. Free is 720x1280. Pro is 1080x1920, plus
    // SRT captions, silence cut list, and full Script Coach.
    // This is a preview unlock for testing. Real payment is
    // Google Play Billing, to be added before Play release.
    public static volatile boolean isPro = false;
    public static int exportWidth() { return isPro ? 1080 : 720; }
    public static int exportHeight() { return isPro ? 1920 : 1280; }
    public static String exportLabel() { return isPro ? "1080x1920 Pro" : "720x1280 Free"; }
    // v0.7.0: SOUNDS and SoundEngine are deleted. Music is now real songs
    // in MusicCatalog.java, played by MusicEngine.java.
    public static final String[] LANG_NAMES = {"Auto", "English", "Persian", "Arabic", "French"};
    public static final String[] LANG_CODES = {"", "en-US", "fa-IR", "ar-SA", "fr-FR"};
    public static volatile int langMode = 0;
    public static volatile String captionLang = "";
    public static final String[] COLOR_NAMES = {"White", "Yellow", "Green", "Cyan", "Orange", "Pink"};
    public static final int[] TEXT_COLORS = {0xFFFFFFFF, 0xFFFFFF00, 0xFF7CFC00, 0xFF00E5FF, 0xFFFF9E2B, 0xFFFF7AB8};
    public static volatile int textColorIndex = 0;
    public static volatile int captionColorIndex = 1;
    public static String langName() { return LANG_NAMES[langMode % LANG_NAMES.length]; }
    public static String langCode() { return LANG_CODES[langMode % LANG_CODES.length]; }
    public static void nextLang() { langMode = (langMode + 1) % LANG_NAMES.length; }
    public static int textColor() { return TEXT_COLORS[textColorIndex % TEXT_COLORS.length]; }
    public static String textColorName() { return COLOR_NAMES[textColorIndex % COLOR_NAMES.length]; }
    public static int captionColor() { return TEXT_COLORS[captionColorIndex % TEXT_COLORS.length]; }
    public static String captionColorName() { return COLOR_NAMES[captionColorIndex % COLOR_NAMES.length]; }
    public static void nextTextColor() { textColorIndex = (textColorIndex + 1) % TEXT_COLORS.length; }
    public static void nextCaptionColor() { captionColorIndex = (captionColorIndex + 1) % TEXT_COLORS.length; }
    // Requests from the overlay to MainActivity. MainActivity owns the result.
    public static final String ACTION_RECORD = "com.talkreel.ar.RECORD";
    public static final String ACTION_PAUSE = "com.talkreel.ar.PAUSE";
    public static final String ACTION_STOP = "com.talkreel.ar.STOP";
}
