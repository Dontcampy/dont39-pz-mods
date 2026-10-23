package com.dont39.pz.loadingprogress.progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWorkTest {
    private final FileWork files = new FileWork();
    // the game's queue items: the hooks describe each from its task
    private final List<Queued> atWorkers = new ArrayList<>();

    private record Queued(FileWork.Task task) {
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private Queued send(String type, String worker, String name, Callable<Object> call) {
        return send(type, worker, name, null, call);
    }

    private Queued send(String type, String worker, String name, String callName, Callable<Object> call) {
        Queued item = new Queued(new FileWork.Task(type, worker, name, callName, new FutureTask<>(call)));
        atWorkers.add(item);
        return item;
    }

    // a worker runs the task's call, from begin to end
    private void run(Queued item, long begins, long ends) {
        run(item, null, begins, ends);
    }

    // named: what the call names inside, or null
    private void run(Queued item, String named, long begins, long ends) {
        FutureTask<?> future = (FutureTask<?>) item.task().future();
        files.callBegins(ms(begins));
        if (named != null) {
            files.callNamed(named);
        }
        future.run();
        Object result = null;
        Throwable thrown = null;
        try {
            result = future.get();
        } catch (Exception e) {
            thrown = e.getCause();
        }
        files.callEnds(item.task().worker(), result, thrown, ms(ends));
    }

    private void pump(long at, int pending) {
        files.pumped(ms(at), atWorkers, pending, Queued::task);
    }

    private void taken(Queued item) {
        atWorkers.remove(item);
    }

    @Test
    void eachTaskDoneDuringTheWaitHasItsWorkerTime() {
        files.track(true, ms(1000), ms(1000));
        Queued early = send("Early", "Early", "e.png", Object::new);
        pump(1010, 0);
        Queued mesh = send("FileTask_LoadMesh", "Model", "C:/m/a.fbx", Object::new);
        Queued shape = send("FileTask_LoadPhysicsShape", "Model", "C:/m/b.fbx", Object::new);
        pump(1020, 0);
        run(early, 1021, 1022);
        taken(early);
        pump(1030, 0);
        files.waitBegins(ms(1040));
        run(shape, 1050, 1350);
        run(mesh, 1040, 1400);
        taken(mesh);
        taken(shape);
        pump(1410, 5);
        AssetWait wait = files.waitEnds(ms(40), ms(400), 1, 2, true);
        assertEquals(List.of(
                new FileWork.Run("FileTask_LoadMesh", "C:/m/a.fbx", ms(20), ms(410), ms(360), false),
                new FileWork.Run("FileTask_LoadPhysicsShape", "C:/m/b.fbx", ms(20), ms(410), ms(300), false)),
                wait.runs(), "done before the wait: not in it");
        assertEquals(1, wait.frames());
        assertEquals(1, wait.workers());
        assertEquals(ms(660), wait.busyNanos());
        assertEquals(new AssetWait(ms(40), ms(400), 1, 2, true, 1, 5, 0, 1, ms(660), 0, 0, wait.runs()), wait);
    }

    @Test
    void theQueueAtTheWaitsFirstFrameIsCounted() {
        files.track(true, 0, 0);
        send("A", "A", "a", Object::new);
        send("B", "B", "b", Object::new);
        pump(10, 7);
        files.waitBegins(ms(20));
        pump(30, 9);
        send("C", "C", "c", Object::new);
        pump(40, 1);
        AssetWait wait = files.waitEnds(ms(20), ms(30), 0, 0, true);
        assertEquals(2, wait.frames());
        assertEquals(9, wait.queuedPending());
        assertEquals(2, wait.queuedAtWorkers());
    }

    @Test
    void tasksAtTheWorkersBeforeTrackingHaveNoQueueTime() {
        files.track(true, 0, ms(100));
        Queued before = send("A", "A", "a", Object::new);
        pump(110, 0);
        Queued after = send("A", "A", "b", Object::new);
        pump(120, 0);
        files.waitBegins(ms(120));
        run(before, 130, 140);
        run(after, 130, 150);
        atWorkers.clear();
        pump(160, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(120), ms(40), 0, 0, true).runs();
        assertEquals(-1, runs.get(0).queued());
        assertEquals(ms(120), runs.get(1).queued());
    }

    @Test
    void aCallThatBeganBeforeTrackingIsNotTimed() {
        Queued item = send("A", "A", "a", Object::new);
        files.track(true, 0, 0);
        files.callBegins(ms(5));
        files.track(false, 0, 0);
        files.track(true, 0, ms(10));
        pump(10, 0);
        files.waitBegins(ms(10));
        ((FutureTask<?>) item.task().future()).run();
        files.callEnds("A", null, null, ms(20));
        atWorkers.clear();
        pump(30, 0);
        assertEquals(-1, files.waitEnds(ms(10), ms(20), 0, 0, true).runs().get(0).workerNanos());
    }

    @Test
    void sharedResultsAreMatchedByHookInTheOrderTheyEnded() {
        files.track(true, 0, 0);
        Queued depthA = send("TileDepthTextures$LoadTask", "Depth", "a.png", () -> null);
        Queued exists = send("FileTask_Exists", "Exists", "x", () -> Boolean.TRUE);
        Queued depthB = send("TileDepthTextures$LoadTask", "Depth", "b.png", () -> null);
        Queued open = send("FileSystemImpl$OpenTask", "Open", "y", () -> Boolean.TRUE);
        pump(0, 0);
        files.waitBegins(ms(1));
        run(depthA, 10, 30);
        run(open, 10, 15);
        run(exists, 10, 12);
        run(depthB, 30, 80);
        atWorkers.clear();
        pump(100, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(1), ms(99), 0, 0, true).runs();
        assertEquals(List.of(ms(20), ms(2), ms(50), ms(5)), runs.stream().map(FileWork.Run::workerNanos).toList());
    }

    @Test
    void sharedResultsAreMatchedByWhatTheCallNamed() {
        files.track(true, 0, 0);
        Queued a = send("TileDepthTextures$LoadTask", "Depth", "C:/m/DEPTH_a.png", "a", () -> null);
        Queued b = send("TileDepthTextures$LoadTask", "Depth", "C:/m/DEPTH_b.png", "b", () -> null);
        Queued again = send("TileDepthTextures$LoadTask", "Depth", "C:/n/DEPTH_a.png", "a", () -> null);
        pump(0, 0);
        files.waitBegins(ms(1));
        run(b, "b", 10, 50);
        run(again, 10, 13);
        run(a, "a", 10, 30);
        atWorkers.clear();
        pump(100, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(1), ms(99), 0, 0, true).runs();
        assertEquals(List.of(ms(20), ms(40), ms(3)), runs.stream().map(FileWork.Run::workerNanos).toList(),
                "by name, in the order sent; a call that named nothing (its tileset was there) by hook");
        assertEquals(0, files.held());
    }

    @Test
    void aCallCancelledWhileItRanIsForgottenAfterAFewPumps() {
        files.track(true, 0, 0);
        Object pixels = new Object();
        Queued cancelled = send("FileTask_LoadPackImage", "PackImage", "p/a", () -> pixels);
        Queued shared = send("FileTask_Exists", "Exists", "x", () -> Boolean.TRUE);
        pump(0, 0);
        files.waitBegins(ms(1));
        files.callBegins(ms(10));
        cancelled.task().future().cancel(true);
        files.callEnds("PackImage", pixels, null, ms(20));
        files.callBegins(ms(10));
        shared.task().future().cancel(true);
        files.callEnds("Exists", Boolean.TRUE, null, ms(20));
        atWorkers.clear();
        pump(30, 0);
        assertEquals(2, files.held(), "a call may end just before the game takes its result");
        for (int i = 1; i < FileWork.KEPT_PUMPS; i++) {
            pump(30 + i, 0);
        }
        assertEquals(0, files.held(), "the result the game dropped is not held");
        Queued next = send("FileTask_Exists", "Exists", "y", () -> Boolean.TRUE);
        pump(40, 0);
        run(next, 41, 42);
        atWorkers.clear();
        pump(50, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(1), ms(49), 0, 0, true).runs();
        assertEquals(List.of(-1L, -1L, ms(1)), runs.stream().map(FileWork.Run::workerNanos).toList(),
                "the next task of the hook gets its own call's time");
    }

    @Test
    void aPackFileIsResolvedOncePerWorldLoad() {
        List<String> asked = new ArrayList<>();
        files.track(true, 0, 0);
        assertEquals("C:/m/a.pack", files.packFile("a", pack -> {
            asked.add(pack);
            return "C:/m/a.pack";
        }));
        assertEquals("C:/m/a.pack", files.packFile("a", pack -> "other"));
        assertNull(files.packFile("none", pack -> {
            asked.add(pack);
            return null;
        }));
        assertNull(files.packFile("none", pack -> "other"), "an unmounted pack too");
        files.track(false, 0, 0);
        files.track(true, 0, 0);
        assertEquals("C:/n/a.pack", files.packFile("a", pack -> "C:/n/a.pack"), "the next load mounts its own");
        assertEquals(List.of("a", "none"), asked);
    }

    @Test
    void theFileACallResolvedNamesItsTask() throws Exception {
        files.track(true, 0, 0);
        Queued resolved = send("FileTask_LoadMesh", "Model", "Skinned/MaleBody", Object::new);
        Queued unresolved = send("FileTask_LoadMesh", "Model", "Static/Box", Object::new);
        pump(0, 0);
        files.waitBegins(ms(1));
        files.callFile("C:/outside/a call.fbx");
        FutureTask<?> future = (FutureTask<?>) resolved.task().future();
        files.callBegins(ms(10));
        files.callFile("C:/mods/A/media/models_X/MaleBody.fbx");
        future.run();
        files.callEnds("Model", future.get(), null, ms(20));
        run(unresolved, 20, 30);
        atWorkers.clear();
        pump(40, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(1), ms(39), 0, 0, true).runs();
        assertEquals(List.of("C:/mods/A/media/models_X/MaleBody.fbx", "Static/Box"),
                runs.stream().map(FileWork.Run::name).toList());
    }

    @Test
    void aTaskThatThrewIsMatchedByWhatItThrew() {
        files.track(true, 0, 0);
        Queued ok = send("A", "A", "ok", Object::new);
        Queued failed = send("A", "A", "failed", () -> {
            throw new IllegalStateException("broken file");
        });
        pump(0, 0);
        files.waitBegins(ms(1));
        run(failed, 10, 40);
        run(ok, 10, 15);
        atWorkers.clear();
        pump(50, 0);
        List<FileWork.Run> runs = files.waitEnds(ms(1), ms(49), 0, 0, true).runs();
        assertEquals(List.of(ms(5), ms(30)), runs.stream().map(FileWork.Run::workerNanos).toList());
        assertEquals(List.of(false, true), runs.stream().map(FileWork.Run::failed).toList());
    }

    @Test
    void aCancelledTaskFailedWithoutWorkerTime() {
        files.track(true, 0, 0);
        Queued item = send("A", "A", "a", Object::new);
        pump(0, 0);
        files.waitBegins(ms(1));
        item.task().future().cancel(false);
        atWorkers.clear();
        pump(10, 0);
        FileWork.Run run = files.waitEnds(ms(1), ms(9), 0, 0, true).runs().get(0);
        assertTrue(run.failed());
        assertEquals(-1, run.workerNanos());
    }

    @Test
    void aTaskTakenBeforeItIsDoneThrows() {
        files.track(true, 0, 0);
        Queued item = send("A", "A", "a", Object::new);
        pump(0, 0);
        taken(item);
        assertThrows(IllegalStateException.class, () -> pump(10, 0));
    }

    @Test
    void nothingIsTrackedWhileOff() {
        Queued item = send("A", "A", "a", Object::new);
        files.waitBegins(0);
        pump(0, 3);
        run(item, 1, 2);
        atWorkers.clear();
        pump(10, 0);
        AssetWait wait = files.waitEnds(0, ms(10), 0, 0, true);
        assertEquals(0, wait.frames());
        assertEquals(List.of(), wait.runs());
        assertEquals(0, wait.busyNanos());
    }

    @Test
    void turningTrackingOffForgetsTheQueue() {
        files.track(true, 0, 0);
        Queued item = send("A", "A", "a", Object::new);
        pump(0, 0);
        files.track(false, 0, 0);
        files.track(true, 0, ms(10));
        taken(item);
        files.waitBegins(ms(10));
        pump(20, 0);
        assertEquals(List.of(), files.waitEnds(ms(10), ms(10), 0, 0, true).runs(), "not sent while tracking");
    }

    @Test
    void theUploadBudgetWaitCountsDuringTheWaitOnly() {
        files.track(true, 0, 0);
        files.throttleBegins(0);
        files.throttleEnds(ms(50));
        files.waitBegins(ms(100));
        files.throttleBegins(ms(100));
        files.throttleEnds(ms(100) + 1000);
        files.throttleBegins(ms(110));
        files.throttleEnds(ms(150));
        AssetWait wait = files.waitEnds(ms(100), ms(60), 0, 0, true);
        assertEquals(ms(40) + 1000, wait.throttleNanos());
        assertEquals(1, wait.throttledCalls(), "the one that slept");
    }

    @Test
    void theWaitCountsNothingWithoutTracking() {
        files.waitBegins(ms(100));
        assertFalse(files.waiting(), "its hooks idle");
        files.throttleBegins(ms(110));
        files.throttleEnds(ms(150));
        assertEquals(0, files.waitEnds(ms(100), ms(60), 0, 0, true).throttleNanos());

        files.track(true, 0, 0);
        files.waitBegins(ms(200));
        assertTrue(files.waiting());
        files.track(false, 0, 0);
        assertFalse(files.waiting(), "the report went off during the wait");
        files.throttleBegins(ms(210));
        files.throttleEnds(ms(250));
        assertEquals(0, files.waitEnds(ms(200), ms(60), 0, 0, true).throttleNanos());
    }
}
