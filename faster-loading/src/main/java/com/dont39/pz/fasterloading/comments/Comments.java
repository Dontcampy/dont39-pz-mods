package com.dont39.pz.fasterloading.comments;

/**
 * {@code ScriptParser.stripComments} of game 42.21 in linear time, with the same output for every input. The game
 * searches its markers backwards in one buffer and cuts each comment out of it, which moves the rest of the text per
 * comment. Here the text left of the latest cut is never changed, so every search runs on the input, and the text
 * right of it is collected once.
 */
public final class Comments {
    private Comments() {
    }

    /** What the game's method returns for {@code text}, which that method also returns unchanged. */
    public static String strip(String text) {
        if (text == null) {
            // the game appends it to its buffer, which reads null as "null"
            text = "null";
        }
        int n = text.length();
        // text[0, kept) is as given; right[r, n) is what follows it after the cuts so far
        int kept = n;
        char[] right = null;
        int r = n;
        int end = lastClose(text, n - 2);
        while (end != -1) {
            int start = lastOpen(text, end - 1);
            if (start == -1) {
                break;
            }
            // the game's nesting: an opener with a closer between it and the one above takes the opener before it
            int top = end - 1;
            while (hasClose(text, start + 1, top)) {
                int inner = start;
                start = lastOpen(text, inner - 2);
                if (start == -1) {
                    break;
                }
                top = inner - 2;
            }
            if (start == -1) {
                break;
            }
            if (right == null) {
                right = new char[n];
            }
            if (end == kept - 1) {
                // a closer formed across the previous cut: its '/' is the first character right of it
                r++;
            } else {
                int length = kept - end - 2;
                r -= length;
                text.getChars(end + 2, kept, right, r);
            }
            kept = start;
            // the game searches from the cut, so its next closer may again span it
            end = kept > 0 && r < n && text.charAt(kept - 1) == '*' && right[r] == '/' ? kept - 1
                    : lastClose(text, kept - 2);
        }
        if (kept == n) {
            return text;
        }
        return new StringBuilder(kept + n - r).append(text, 0, kept).append(right, r, n - r).toString();
    }

    // the last "*/" starting at or before from
    private static int lastClose(String text, int from) {
        for (int i = Math.min(from, text.length() - 2); i >= 0; i--) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                return i;
            }
        }
        return -1;
    }

    // the last "/*" starting at or before from
    private static int lastOpen(String text, int from) {
        for (int i = Math.min(from, text.length() - 2); i >= 0; i--) {
            if (text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                return i;
            }
        }
        return -1;
    }

    // whether a "*/" starts in [from, to]
    private static boolean hasClose(String text, int from, int to) {
        for (int i = Math.min(to, text.length() - 2); i >= from; i--) {
            if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                return true;
            }
        }
        return false;
    }
}
