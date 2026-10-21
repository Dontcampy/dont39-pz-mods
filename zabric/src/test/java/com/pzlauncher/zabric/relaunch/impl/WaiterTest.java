package com.pzlauncher.zabric.relaunch.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaiterTest {
    private static final LaunchSpec SPEC = new LaunchSpec(77, "pz.exe", "pz.exe -debug", Path.of("dir"), Map.of(),
            Path.of("zabric"));

    /** Records what the helper did; {@code exited} answers awaitExit, {@code waitError} and {@code startError} fail. */
    private static final class Fake implements Waiter.Effects {
        final List<String> calls = new ArrayList<>();
        boolean exited = true;
        Exception waitError;
        Exception startError;

        @Override
        public boolean awaitExit(long pid, Duration wait) throws Exception {
            calls.add("await " + pid + " " + wait);
            if (waitError != null) {
                throw waitError;
            }
            return exited;
        }

        @Override
        public void start(LaunchSpec spec) throws Exception {
            calls.add("start " + spec.commandLine());
            if (startError != null) {
                throw startError;
            }
        }

        @Override
        public void record(LaunchSpec spec, String reason) {
            calls.add("record " + reason);
        }
    }

    @Test
    void theGameStartsAgainOnceTheOldOneExited() {
        Fake fake = new Fake();
        Waiter.run(SPEC, fake, Duration.ofMinutes(2));
        assertEquals(List.of("await 77 PT2M", "start pz.exe -debug"), fake.calls);
    }

    @Test
    void aGameStillRunningAfterTheWaitIsRecordedAndNotStartedTwice() {
        Fake fake = new Fake();
        fake.exited = false;
        Waiter.run(SPEC, fake, Duration.ofMinutes(2));
        assertEquals(List.of("await 77 PT2M",
                "record the game (process 77) was still running 2 minutes after it quit to restart"), fake.calls);
    }

    @Test
    void aFailedWaitIsRecorded() {
        Fake fake = new Fake();
        fake.waitError = new IllegalStateException("no handle");
        Waiter.run(SPEC, fake, Duration.ofMinutes(2));
        assertEquals(List.of("await 77 PT2M",
                "record cannot wait for the game (process 77) to exit: java.lang.IllegalStateException: no handle"),
                fake.calls);
    }

    @Test
    void aFailedStartIsRecorded() {
        Fake fake = new Fake();
        fake.startError = new IOException("Windows error 2");
        Waiter.run(SPEC, fake, Duration.ofMinutes(2));
        assertEquals(List.of("await 77 PT2M", "start pz.exe -debug",
                "record cannot start the game again: java.io.IOException: Windows error 2"), fake.calls);
    }
}
