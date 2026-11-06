package com.talkreel.ar;

// Lightweight on-device AI. No server, no heavy model, so it stays fast.
// 1. Hook score in English, Persian and Arabic.
// 2. Speech rate from captions, to nudge the teleprompter speed.
// 3. Silence state from mic level plus caption timeout.
public class AiEngine {
    public static int hookScore(String script, int seconds) {
        if (script == null) script = "";
        String s = script.trim().toLowerCase();
        int score = 50;
        if (s.startsWith("stop") || s.startsWith("wait") || s.startsWith("how") || s.startsWith("why")
            || s.startsWith("look") || s.startsWith("this is")) score += 20;
        // Persian hooks: wait, how, why
        if (s.contains("\u0635\u0628\u0631") || s.contains("\u0686\u0637\u0648\u0631") || s.contains("\u0686\u0631\u0627")) score += 20;
        // Arabic hooks: wait, how, why
        if (s.contains("\u0627\u0646\u062a\u0638\u0631") || s.contains("\u0643\u064a\u0641") || s.contains("\u0644\u0645\u0627\u0630\u0627")) score += 20;
        if (s.length() > 40) score += 10;
        if (seconds >= 15 && seconds <= 60) score += 10;
        if (score > 100) score = 100;
        if (score < 0) score = 0;
        return score;
    }

    // Words per minute from caption words in the last window.
    // Returns a speed multiplier: slow speech -> slower scroll, fast -> faster.
    public static float speedFromWords(int wordsInLast10s) {
        if (wordsInLast10s <= 8) return 0.7f;
        if (wordsInLast10s <= 18) return 1.0f;
        if (wordsInLast10s <= 28) return 1.3f;
        return 1.5f;
    }

    public static String shortStatus(int amp, boolean captionHeardRecently) {
        if (amp < 200 && !captionHeardRecently) return "quiet";
        return "voice";
    }
}
