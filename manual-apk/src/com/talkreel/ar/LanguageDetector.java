package com.talkreel.ar;

// Fast, on-device language detection from the caption text itself.
// No server, no model download. It looks at the script and at
// letters that only exist in one language.
// Persian has g, ch, p, zh: U+06AF U+0686 U+067E U+0698
// Arabic has teh marbuta, alef maksura: U+0629 U+0649, and no Persian letters.
// French has accented letters and common words.
public class LanguageDetector {
    public static String detect(String text) {
        if (text == null || text.trim().length() < 2) return "";
        int arabic = 0;
        int persianOnly = 0;
        int latin = 0;
        int frenchAccent = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u06AF' || ch == '\u0686' || ch == '\u067E' || ch == '\u0698') persianOnly++;
            if (ch >= '\u0600' && ch <= '\u06FF') arabic++;
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) latin++;
            if (ch == '\u00E9' || ch == '\u00E8' || ch == '\u00EA' || ch == '\u00E0' || ch == '\u00E7' || ch == '\u00FB') frenchAccent++;
        }
        if (persianOnly > 0) return "fa-IR";
        if (arabic >= 3) return "ar-SA";
        String low = text.toLowerCase();
        if (frenchAccent >= 2) return "fr-FR";
        if (low.contains(" bonjour") || low.contains(" merci") || low.contains(" je suis") || low.startsWith("bonjour")) return "fr-FR";
        if (latin >= 3) return "en-US";
        return "";
    }

    public static boolean isRtl(String lang) {
        return "fa-IR".equals(lang) || "ar-SA".equals(lang);
    }

    public static boolean isArabicScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u0600' && ch <= '\u06FF') return true;
        }
        return false;
    }
}
