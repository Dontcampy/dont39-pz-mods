package com.dont39.pz.loadingprogress.report;

import com.dont39.pz.loadingprogress.progress.AssetWait;
import com.dont39.pz.loadingprogress.progress.FileWork;
import com.dont39.pz.loadingprogress.progress.Flow;
import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import com.dont39.pz.loadingprogress.progress.Timed;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
                null, gap, timed, waited, null);
    }

    private static Report.Context context(Map<String, String> folders) {
        return new Report.Context("launch: done after 1.0 s", "42.21.0", "0.1.0", "C:\\Game", folders);
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
                "flow,step,kind,index,total,name,mod,start_ms,duration_ms,task_type,worker_ms,task_failed",
                "launch,loading_world,step,11,13,Loading the world,,0.000,20.000,,,",
                "launch,loading_world,world,0,0,\"Say \"\"hi\"\", world\",,20.000,30.500,,,",
                "launch,loading_world,chunk,3,9,,,50.500,1.000,,,",
                "launch,lua,lua,2,5,C:/mods/A/42/media/lua/x.lua,A,51.500,0.250,,,"), csv.lines().toList());
        assertTrue(csv.endsWith("\r\n"));
    }

    private static long clock(int second, int milli) {
        return LocalDateTime.of(2026, 10, 21, 21, 10, second, milli * 1_000_000).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    private static Progress.End withWait(AssetWait wait) {
        Progress.Gap gap = new Progress.Gap(0, Step.LOADING_WORLD, null, null, 0);
        return new Progress.End(Flow.START, Progress.Outcome.DONE, null, ms(120_000), 13, Step.ENTERING_WORLD, 0, 0, 0,
                gap, gap, null, gap, List.of(step(Step.LOADING_WORLD, 11, 13, 0, 120_000)), 0, wait);
    }

    @Test
    void theAssetWaitHasItsSpanTasksAndMods() {
        List<FileWork.Run> runs = List.of(
                new FileWork.Run("FileTask_LoadPhysicsShape", "C:/mods/A/42/media/models_X/Car.fbx", ms(73_000),
                        ms(80_000), ms(3000), false),
                new FileWork.Run("FileTask_LoadPhysicsShape", "C:/mods/A/42/media/models_X/Van.fbx", ms(73_000),
                        ms(81_000), ms(1500.5), true),
                new FileWork.Run("FileTask_LoadPackImage", "Tiles2x/floor_1", -1, ms(74_000), ms(40), false),
                new FileWork.Run("TileDepthTextures$LoadTask", "C:/game/media/depth/DEPTH_a.png", ms(73_500),
                        ms(75_000), -1, false));
        AssetWait wait = new AssetWait(ms(73_920), ms(39_758), clock(15, 863), clock(55, 621), true, 1000, 2300, 16,
                4, ms(15_903.2), ms(250), 7, runs);
        Progress.End end = withWait(wait);
        Report.Context context = context(Map.of("A", "C:/mods/A"));
        String summary = Report.summary(end, context);
        assertTrue(summary.contains("   39758 ms  from 21:10:15.863 to 21:10:55.621, 73920 ms after the load began\n"),
                summary);
        assertTrue(summary.contains("  1000 frames pumped the file queue, one per 40 ms on average\n"), summary);
        assertTrue(summary.contains("  At its first frame 2316 file tasks queued: 2300 waiting for a worker, 16 at"
                + " the workers\n"), summary);
        assertTrue(summary.contains("  4 file tasks done during it; 4 worker threads busy 15903 ms (10% of their"
                + " time)\n"), summary);
        assertTrue(summary.contains("upload budget 250 ms, 7 times longer than a moment"), summary);
        assertTrue(summary.contains("  1 task not timed on a worker"), summary);
        assertTrue(summary.contains("      4501 ms  FileTask_LoadPhysicsShape: 2 tasks (1 failed)\n"), summary);
        assertTrue(summary.indexOf("FileTask_LoadPhysicsShape: 2") < summary.indexOf("FileTask_LoadPackImage: 1"),
                "the type with the most worker time first: " + summary);
        assertTrue(summary.contains("      3000 ms  FileTask_LoadPhysicsShape  C:/mods/A/42/media/models_X/Car.fbx"
                + "  [A]\n"), summary);
        assertTrue(summary.contains("Van.fbx  [A]  (failed)\n"), summary);
        assertFalse(summary.contains("DEPTH_a.png"), "not timed, not among the slowest: " + summary);
        assertTrue(summary.contains("TileDepthTextures$LoadTask: 1 task (1 not timed)\n"), summary);
        assertTrue(summary.contains("      4501 ms  A  (2 tasks)\n"), summary);
        assertTrue(summary.contains("         0 ms  the game's own, in its folder  (1 task)\n"), summary);
        assertTrue(summary.contains("        40 ms  named without a file's path, so in no folder  (1 task)\n"),
                summary);
        assertFalse(summary.contains("in no mod's or the game's folder"), "only when there are such: " + summary);
        assertTrue(summary.indexOf("Asset wait") < summary.indexOf("Items by kind"), "after the steps: " + summary);

        List<String> csv = Report.csv(end, context).lines().toList();
        assertEquals(List.of(
                "start,loading_world,file_task,0,0,C:/mods/A/42/media/models_X/Car.fbx,A,73000.000,7000.000,"
                        + "FileTask_LoadPhysicsShape,3000.000,",
                "start,loading_world,file_task,0,0,C:/mods/A/42/media/models_X/Van.fbx,A,73000.000,8000.000,"
                        + "FileTask_LoadPhysicsShape,1500.500,failed",
                "start,loading_world,file_task,0,0,Tiles2x/floor_1,,,,FileTask_LoadPackImage,40.000,",
                "start,loading_world,file_task,0,0,C:/game/media/depth/DEPTH_a.png,,73500.000,1500.000,"
                        + "TileDepthTextures$LoadTask,,"),
                csv.subList(2, csv.size()));
    }

    @Test
    void packImagesCountForTheModWhosePackFileTheGameOpened() {
        List<FileWork.Run> runs = List.of(
                new FileWork.Run("FileTask_LoadPackImage", "C:\\mods\\Tiki\\42\\media\\texturepacks\\tiki.pack/tiki167",
                        ms(73_000), ms(74_000), ms(300), false),
                new FileWork.Run("FileTask_LoadPackImage", "C:/mods/Tiki/42/media/texturepacks/tiki.pack/tiki168",
                        ms(73_000), ms(74_000), ms(200), false),
                new FileWork.Run("FileTask_LoadPackImage", "C:\\Game\\media\\texturepacks\\Tiles2x.pack/floor_1",
                        ms(73_000), ms(74_000), ms(120), false),
                new FileWork.Run("FileTask_LoadAnimation", "c:/game/media/anims_X/Bob/Idle.X", ms(73_000), ms(74_000),
                        ms(80), false),
                new FileWork.Run("FileTask_LoadPackImage", "C:/Game/mods/Local/media/texturepacks/l.pack/l_0",
                        ms(73_000), ms(74_000), ms(50), false),
                new FileWork.Run("FileTask_LoadImageData", "D:/Zomboid/cache/map.png", ms(73_000), ms(74_000),
                        ms(30), false),
                new FileWork.Run("FileTask_LoadImageData", "media/textures/x.png", ms(73_000), ms(74_000), ms(7),
                        false),
                new FileWork.Run("FileSystemImpl$CloseTask", null, ms(73_000), ms(74_000), ms(1), false));
        AssetWait wait = new AssetWait(ms(73_000), ms(1000), clock(15, 0), clock(16, 0), true, 10, 0, 8, 4, ms(788),
                0, 0, runs);
        Progress.End end = withWait(wait);
        Report.Context context = context(Map.of("Tiki", "C:/mods/Tiki", "Local", "C:/Game/mods/Local"));
        String summary = Report.summary(end, context);
        String perMod = summary.substring(summary.indexOf("  Worker time per mod"));
        assertEquals("""
                  Worker time per mod, by the folder that holds the task's file
                       500 ms  Tiki  (2 tasks)
                        50 ms  Local  (1 task)
                       200 ms  the game's own, in its folder  (2 tasks)
                        30 ms  in no mod's or the game's folder  (1 task)
                         8 ms  named without a file's path, so in no folder  (2 tasks)
                """, perMod.substring(0, perMod.indexOf("\nItems by kind")));
        assertTrue(summary.contains("tiki.pack/tiki167  [Tiki]\n"), summary);

        List<String> csv = Report.csv(end, context).lines().toList();
        assertEquals("start,loading_world,file_task,0,0,C:/mods/Tiki/42/media/texturepacks/tiki.pack/tiki168,Tiki,"
                + "73000.000,1000.000,FileTask_LoadPackImage,200.000,", csv.get(3));
        assertTrue(csv.get(4).contains("Tiles2x.pack/floor_1,,"), "the game's own names no mod: " + csv.get(4));
    }

    @Test
    void onlyAbsolutePathsAreFiles() {
        assertTrue(ModFolders.isFile("C:\\Game\\media\\a.png"));
        assertTrue(ModFolders.isFile("e:/mods/a.pack/b"));
        assertTrue(ModFolders.isFile("/home/pz/Zomboid/mods/a.png"));
        assertTrue(ModFolders.isFile("\\\\server\\share\\a.png"));
        assertFalse(ModFolders.isFile("Tiles2x/floor_1"));
        assertFalse(ModFolders.isFile("media/textures/x.png"));
        assertFalse(ModFolders.isFile("C:relative.png"));
        assertFalse(ModFolders.isFile(""));
        assertFalse(ModFolders.isFile(null));
    }

    @Test
    void anUnfinishedAssetWaitSaysSo() {
        AssetWait wait = new AssetWait(ms(1000), ms(500), clock(15, 863), 0, false, 0, 0, 0, 0, 0, 0, 0, List.of());
        String summary = Report.summary(withWait(wait), context(Map.of()));
        assertTrue(summary.contains("     500 ms  from 21:10:15.863, until the load ended during it, 1000 ms after"
                + " the load began\n"), summary);
        assertTrue(summary.contains("  0 frames pumped the file queue\n"), summary);
        assertTrue(summary.contains("0 worker threads busy 0 ms\n"), summary);
    }

    @Test
    void fieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("plain", Report.field("plain"));
        assertEquals("", Report.field(null));
        assertEquals("\"a\nb\"", Report.field("a\nb"));
        assertEquals("\"a,b\"", Report.field("a,b"));
    }
}
