package com.dont39.pz.loadingprogress.report;

import com.dont39.pz.loadingprogress.progress.Flow;
import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import com.dont39.pz.loadingprogress.progress.Timed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {
    private static long ms(double millis) {
        return (long) (millis * TimeUnit.MILLISECONDS.toNanos(1));
    }

    private static Timed step(Step step, int number, int steps, double start, double millis) {
        return new Timed(new Line(step, null, number, steps, null), null, ms(start), ms(millis));
    }

    private static Timed item(Step step, Item kind, int index, int total, String name, String path, double start,
                              double millis) {
        return new Timed(new Line(step, kind, index, total, name), path, ms(start), ms(millis));
    }

    private static Progress.End end(List<Timed> timed, long nanos, long waited) {
        Progress.Gap gap = new Progress.Gap(0, Step.MODS, null, null, 0);
        return new Progress.End(Flow.LAUNCH, Progress.Outcome.DONE, null, nanos, 10, Step.GAME_BOOT, 0, 0, 0, gap, gap,
                null, gap, timed, waited);
    }

    private static Report.Context context(Map<String, String> folders) {
        return new Report.Context("launch: done after 1.0 s", "42.21.0", "0.1.0", folders);
    }

    @Test
    void eachKindListsItsSlowestFirst() {
        List<Timed> timed = new ArrayList<>();
        timed.add(step(Step.LUA, 7, 10, 0, 1));
        for (int i = 1; i <= 25; i++) {
            timed.add(item(Step.LUA, Item.LUA, i, 25, String.format("a%02d.lua", i), "C:/game/a.lua", i, i));
        }
        String summary = Report.summary(end(timed, ms(400), 0), context(Map.of()));
        assertTrue(summary.contains("  Lua files: 25, 325 ms\n"), summary);
        assertTrue(summary.indexOf("a25.lua") < summary.indexOf("a24.lua"), summary);
        assertTrue(summary.contains("Lua file 6 / 25: a06.lua"), summary);
        assertFalse(summary.contains("a05.lua"), "only the " + Report.SLOWEST + " slowest");
        assertTrue(summary.contains("326 ms  7 / 10  Loading Lua"), "a step's time is its lines' " + summary);
    }

    @Test
    void modsAddUpTheirJarItsLoadAndTheFilesInTheirFolder() {
        Map<String, String> folders = new HashMap<>();
        folders.put("A", "C:\\Mods\\A");
        folders.put("B", "C:/mods/B/");
        folders.put("Gone", null);
        List<Timed> timed = List.of(
                step(Step.MODS, 1, 10, 0, 0),
                item(Step.MODS, Item.JAVA_MOD, 1, 1, "A", null, 0, 300),
                item(Step.MODS, Item.MOD, 1, 2, "A", null, 300, 10),
                item(Step.MODS, Item.MOD, 2, 2, "B", null, 310, 20),
                step(Step.SCRIPTS, 4, 10, 330, 0),
                item(Step.SCRIPTS, Item.SCRIPT, 1, 2, "s.txt", "C:/mods/B/42/media/scripts/s.txt", 330, 500),
                item(Step.SCRIPTS, Item.SCRIPT, 2, 2, "t.txt", "C:/mods/B/42/media/scripts/t.txt", 830, 40),
                step(Step.LUA, 7, 10, 830, 0),
                item(Step.LUA, Item.LUA, 1, 3, "x.lua", "c:/mods/a/42/media/lua/client/x.lua", 830, 50),
                item(Step.LUA, Item.LUA, 2, 3, "y.lua", "C:/game/media/lua/client/y.lua", 880, 70),
                item(Step.LUA, Item.LUA, 3, 3, "z.lua", "C:/mods/AB/42/media/lua/client/z.lua", 950, 5));
        String summary = Report.summary(end(timed, ms(955), 0), context(folders));
        String b = "520 ms  B  (mod 20 ms, script files 500 ms)\n";
        String a = "360 ms  A  (Java mod 300 ms, mod 10 ms, Lua files 50 ms)\n";
        assertTrue(summary.contains(b) && summary.contains(a), summary);
        assertTrue(summary.indexOf(b) < summary.indexOf(a), "slowest mod first: " + summary);
        assertTrue(summary.contains("75 ms  Lua and script files in no mod's folder"), summary);
        assertTrue(summary.contains("x.lua  [A]"), "a file names its mod: " + summary);
        assertTrue(summary.contains("40 ms  the last script file and the game's processing"), summary);
        assertTrue(summary.contains("t.txt  (and the game's processing"), summary);
        assertTrue(summary.contains("3 mods in the mod list; 1 Java mod in its Java mod pass"), summary);
    }

    @Test
    void aStepWithItemsShowsItsTimeOutsideThem() {
        List<Timed> timed = List.of(
                step(Step.MODS, 1, 10, 0, 100),
                item(Step.MODS, Item.MOD, 1, 1, "A", null, 100, 50),
                step(Step.MODS, 1, 10, 150, 200),
                step(Step.TRANSLATIONS, 2, 10, 350, 30));
        String summary = Report.summary(end(timed, ms(380), 0), context(Map.of()));
        assertTrue(summary.contains("350 ms  1 / 10  Loading mods  (outside its items 300 ms)\n"), summary);
        assertTrue(summary.contains("30 ms  2 / 10  Loading translations\n"), "no items, no share: " + summary);
    }

    @Test
    void theSummaryNamesTheWaitAndTheFrameLine() {
        String summary = Report.summary(end(List.of(step(Step.MODS, 1, 10, 0, 100)), ms(3100), ms(3000)),
                context(Map.of()));
        assertTrue(summary.contains("Total 3100 ms, of it waiting in the menu 3000 ms"), summary);
        assertTrue(summary.contains("console.txt: launch: done after 1.0 s"), summary);
        assertTrue(summary.contains("Game 42.21.0; 0 mods in the mod list; no Java mod pass"), summary);
        assertTrue(summary.startsWith("Loading Progress 0.1.0"), summary);
    }

    @Test
    void theCsvHasARowPerLine() {
        Map<String, String> folders = Map.of("A", "C:/mods/A");
        List<Timed> timed = List.of(
                step(Step.LOADING_WORLD, 11, 13, 0, 20),
                item(Step.LOADING_WORLD, Item.WORLD, 0, 0, "Say \"hi\", world", null, 20, 30.5),
                item(Step.LOADING_WORLD, Item.CHUNK, 3, 9, null, null, 50.5, 1),
                item(Step.LUA, Item.LUA, 2, 5, "x.lua", "C:/mods/A/42/media/lua/x.lua", 51.5, 0.25));
        String csv = Report.csv(end(timed, ms(60), 0), context(folders));
        assertEquals(List.of(
                "flow,step,kind,index,total,name,mod,start_ms,duration_ms",
                "launch,loading_world,step,11,13,Loading the world,,0.000,20.000",
                "launch,loading_world,world,0,0,\"Say \"\"hi\"\", world\",,20.000,30.500",
                "launch,loading_world,chunk,3,9,,,50.500,1.000",
                "launch,lua,lua,2,5,C:/mods/A/42/media/lua/x.lua,A,51.500,0.250"), csv.lines().toList());
        assertTrue(csv.endsWith("\r\n"));
    }

    @Test
    void fieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("plain", Report.field("plain"));
        assertEquals("", Report.field(null));
        assertEquals("\"a\nb\"", Report.field("a\nb"));
        assertEquals("\"a,b\"", Report.field("a,b"));
    }
}
