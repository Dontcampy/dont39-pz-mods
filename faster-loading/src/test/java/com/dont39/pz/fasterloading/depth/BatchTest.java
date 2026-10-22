package com.dont39.pz.fasterloading.depth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchTest {
    private static final Object MELOS = new Object();
    private static final Object GAME = new Object();
    private static final Map<String, Integer> MELOS_ROWS = Map.of("melos_01", 4, "melos_02", 2);
    private static final Map<String, Integer> GAME_ROWS = Map.of("walls_01", 8, "signs_one-off_02", 64);

    private static Batch.Task melos(String tileset) {
        return new Batch.Task(MELOS, "melos", MELOS_ROWS, "DEPTH_" + tileset + ".png", Schedule.GAME_PRIORITY);
    }

    private static Batch.Task game(String tileset) {
        return new Batch.Task(GAME, "game", GAME_ROWS, "DEPTH_" + tileset + ".png", Schedule.GAME_PRIORITY);
    }

    private static List<Batch.Task> batch() {
        return List.of(melos("melos_01"), melos("melos_02"), game("walls_01"), game("signs_one-off_02"));
    }

    private static String failure(List<Batch.Task> tasks, int queued) {
        return assertThrows(IllegalStateException.class, () -> Batch.plan(tasks, queued, 4)).getMessage();
    }

    @Test
    void theTilesetIsTheGames() {
        assertEquals("signs_one-off_02", Batch.tileset("DEPTH_signs_one-off_02.png"));
        assertEquals("walls_01", Batch.tileset("DEPTH_walls_01.png"));
    }

    @Test
    void aBatchIsPlanned() {
        Batch.Plan plan = Batch.plan(batch(), 4, 4);
        assertEquals(2, plan.chains());
        assertEquals("game", plan.longestMod());
        assertEquals(2, plan.longestTasks());
        assertEquals(Schedule.GAME_PRIORITY, plan.priorities()[2], "the game's chain has the most rows");
        assertTrue(Arrays.stream(plan.priorities()).allMatch(p -> p <= Schedule.GAME_PRIORITY));
    }

    @Test
    void instancesAreToldApartByIdentity() {
        Batch.Task other = new Batch.Task(new Object(), "melos", MELOS_ROWS, "DEPTH_melos_02.png",
                Schedule.GAME_PRIORITY);
        assertEquals(2, Batch.plan(List.of(melos("melos_01"), other), 2, 4).chains());
    }

    @Test
    void anEmptyBatchIsPlanned() {
        Batch.Plan plan = Batch.plan(List.of(), 0, 4);
        assertEquals(0, plan.chains());
        assertEquals(0, plan.priorities().length);
    }

    @Test
    void anotherCountIsReported() {
        String message = failure(batch(), 5);
        assertTrue(message.contains("counted 5") && message.contains("holds 4")
                && message.contains("DEPTH_melos_01.png of melos"), message);
        assertTrue(failure(List.of(), 1).contains("holds 0"));
    }

    @Test
    void anotherPriorityIsReported() {
        List<Batch.Task> tasks = new ArrayList<>(batch());
        tasks.set(3, new Batch.Task(GAME, "game", GAME_ROWS, "DEPTH_signs_one-off_02.png", 5));
        String message = failure(tasks, 4);
        assertTrue(message.contains("DEPTH_signs_one-off_02.png of game") && message.contains("priority 5"), message);
    }

    @Test
    void aTilesetWithoutRowsIsReported() {
        List<Batch.Task> tasks = new ArrayList<>(batch());
        tasks.set(1, melos("melos_03"));
        String message = failure(tasks, 4);
        assertTrue(message.contains("DEPTH_melos_03.png of melos") && message.contains("no entry for melos_03"), message);
    }
}
