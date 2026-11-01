package com.gacs.backend.service.constraintparse;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Puts text into one predictable form before parsing, and remembers where each character came from:
 * <ul>
 *   <li>Unicode NFKC, so full-width and look-alike compatibility characters ("Ｃritical", "１０pm") become plain
 *       ones;</li>
 *   <li>invisible format characters (zero-width spaces, right-to-left overrides) and control characters
 *       removed;</li>
 *   <li>curly quotes and dashes made plain, lowercase, and whitespace collapsed to single spaces.</li>
 * </ul>
 */
final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Normalised text plus, for each of its characters, the range of the original text it came from. */
    record Normalized(String original, String text, int[] originalStart, int[] originalEnd) {

        /** Start offset in the original text of normalised position {@code from}. */
        int originalStartOf(int from) {
            return text.isEmpty() ? 0 : originalStart[Math.min(from, text.length() - 1)];
        }

        /** End offset in the original text of normalised range ending at {@code to} (exclusive). */
        int originalEndOf(int to) {
            return text.isEmpty() ? 0 : originalEnd[Math.max(0, Math.min(to, text.length()) - 1)];
        }

        /** The original text behind normalised range [from, to). */
        String originalSlice(int from, int to) {
            return original.substring(originalStartOf(from), originalEndOf(to));
        }
    }

    static Normalized normalize(String original) {
        StringBuilder out = new StringBuilder(original.length());
        int[] starts = new int[original.length() * 4 + 4];
        int[] ends = new int[starts.length];

        for (int i = 0; i < original.length(); ) {
            int codePoint = original.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            if (!dropped(codePoint)) {
                String piece = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT);
                for (int k = 0; k < piece.length(); k++) {
                    char c = plain(piece.charAt(k));
                    if (Character.isWhitespace(c)) {
                        if (out.isEmpty() || out.charAt(out.length() - 1) == ' ') {
                            continue;
                        }
                        c = ' ';
                    }
                    if (out.length() == starts.length) {
                        starts = Arrays.copyOf(starts, starts.length * 2);
                        ends = Arrays.copyOf(ends, ends.length * 2);
                    }
                    starts[out.length()] = i;
                    ends[out.length()] = next;
                    out.append(c);
                }
            }
            i = next;
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == ' ') {
            length--;
        }
        return new Normalized(original, out.substring(0, length), Arrays.copyOf(starts, length),
                Arrays.copyOf(ends, length));
    }

    /** Invisible formatting (zero-width, bidi overrides) and control characters other than whitespace. */
    private static boolean dropped(int codePoint) {
        return Character.getType(codePoint) == Character.FORMAT
                || (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint));
    }

    private static char plain(char c) {
        return switch (c) {
            case '‘', '’', '‛', 'ʼ', '`', '´' -> '\'';
            case '“', '”', '„' -> '"';
            case '‐', '‑', '‒', '–', '—', '―', '−' -> '-';
            default -> c;
        };
    }
}
