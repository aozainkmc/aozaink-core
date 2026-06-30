package com.aozainkmc.core.recognizer;

public final class StrokeCountGlyphPatch {

    private StrokeCountGlyphPatch() {}

    public static String correct(String glyph, int strokeCount) {
        if (!isHorizontalNumber(glyph)) {
            return glyph;
        }
        return switch (strokeCount) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            default -> glyph;
        };
    }

    private static boolean isHorizontalNumber(String glyph) {
        return "一".equals(glyph) || "二".equals(glyph) || "三".equals(glyph);
    }
}
