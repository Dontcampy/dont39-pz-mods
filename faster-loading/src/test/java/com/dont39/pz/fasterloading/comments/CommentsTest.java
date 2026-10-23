package com.dont39.pz.fasterloading.comments;

import org.junit.jupiter.api.Test;
import zombie.scripting.ScriptParser;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * {@link Comments#strip} against the game's own method, which is the reference for every input. The hook hands the
 * result to the game's method, so that must also return it unchanged.
 */
class CommentsTest {
    static void same(String text) {
        String game = ScriptParser.stripComments(text);
        String ours = Comments.strip(text);
        if (!game.equals(ours)) {
            assertEquals(game, ours, "for " + quoted(text));
        }
        String again = ScriptParser.stripComments(ours);
        if (!again.equals(ours)) {
            assertEquals(ours, again, "the game's method changes the result for " + quoted(text));
        }
    }

    static String quoted(String text) {
        return "\"" + text.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    // what the game does, pinned so a change of the reference shows here and not as a passing comparison
    @Test
    void theGameQuirksAreReproduced() {
        String[][] cases = {
                {"s \"/* in string */\" t", "s \"\" t"},
                {"a // line /* b */ c", "a // line  c"},
                {"a /* b /* c */ d */ e", "a  e"},
                {"a /* 1 /* 2 */ 3 /* 4 */ 5 */ b", "a  b"},
                {"x /* /* /* y */ */ */ z", "x /*  z"},
                {"a /* b", "a /* b"},
                {"a /* x /* y */ b", "a /* x  b"},
                {"a /* x */ */ b", "a /* x */ */ b"},
                {"a /* 1 */ b */ c /* 2 */ d", "a /* 1 */ b */ c  d"},
                {"a /*/ b", "a  b"},
                {"/*/ x */ y", "/*/ x */ y"},
                {"/* x */ a*/* c *//b", "/* x */ a*/b"},
                {"k /* x */ a/* c */*z */ m", "k  a/* c * m"},
                {"a\r\n/* b\r\n c */\r\nd", "a\r\n\r\nd"},
                {"", ""},
                {"*/", "*/"},
                {"/**/", ""},
                {"/*/", ""},
        };
        for (String[] c : cases) {
            assertEquals(c[1], ScriptParser.stripComments(c[0]), "the game for " + quoted(c[0]));
            assertEquals(c[1], Comments.strip(c[0]), "for " + quoted(c[0]));
            same(c[0]);
        }
    }

    @Test
    void nullIsReadAsTheGameReadsIt() {
        assertEquals(ScriptParser.stripComments(null), Comments.strip(null));
        assertEquals(Comments.strip(null), ScriptParser.stripComments(Comments.strip(null)));
    }

    @Test
    void textWithoutCommentsIsReturnedAsItIs() {
        String text = "module Base {\n  item Axe { Weight = 3, }\n}\n";
        assertEquals(text, Comments.strip(text));
    }

    // every string up to this length over the characters that make markers, plus one that does not
    @Test
    void everyShortStringOfMarkerCharacters() {
        char[] alphabet = {'/', '*', 'x'};
        for (int length = 0; length <= 12; length++) {
            char[] text = new char[length];
            int count = (int) Math.pow(alphabet.length, length);
            for (int i = 0; i < count; i++) {
                int v = i;
                for (int j = 0; j < length; j++) {
                    text[j] = alphabet[v % alphabet.length];
                    v /= alphabet.length;
                }
                same(new String(text));
            }
        }
    }

    @Test
    void randomText() {
        Random random = new Random(42);
        String[] pieces = {"/*", "*/", "/", "*", "a", " ", "\"", "\n", "\r\n", "//", "/*/", "*/*", "\u00e9", "\u4e2d"};
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(i % 10 == 0 ? 400 : 40);
            for (int j = 0; j < length; j++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            same(text.toString());
        }
    }

    // comment blocks nested to random depths, with stray markers between them
    @Test
    void randomNesting() {
        Random random = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            StringBuilder text = new StringBuilder();
            int blocks = 1 + random.nextInt(6);
            for (int b = 0; b < blocks; b++) {
                nested(text, random, random.nextInt(5));
                if (random.nextInt(8) == 0) {
                    text.append(random.nextBoolean() ? "*/" : "/*");
                }
                text.append(" t ");
            }
            same(text.toString());
        }
    }

    // runs of markers, where the game's searches and cuts are densest
    @Test
    void longRunsOfMarkers() {
        String[] units = {"/*", "*/", "/*/", "*/*", "/**/", "/*x*/", "*//*"};
        for (String a : units) {
            for (String b : units) {
                same(a.repeat(300) + b.repeat(300));
                same(a.repeat(300) + " y " + b.repeat(150) + a.repeat(150));
            }
        }
    }

    // the cuts never move the rest of the text: a million characters of markers take milliseconds
    @Test
    void linearOnTheWorstMarkerRuns() {
        for (String text : new String[] {"/*".repeat(250_000) + "*/".repeat(250_000), "/*/".repeat(330_000),
                "/*x*/".repeat(200_000), "*/*".repeat(330_000)}) {
            assertTimeout(Duration.ofSeconds(5), () -> Comments.strip(text));
        }
    }

    private static void nested(StringBuilder text, Random random, int depth) {
        text.append("/*");
        int parts = random.nextInt(3);
        for (int p = 0; p < parts; p++) {
            text.append(" c ");
            if (depth > 0 && random.nextBoolean()) {
                nested(text, random, depth - 1);
            }
        }
        text.append(" c */");
    }
}
