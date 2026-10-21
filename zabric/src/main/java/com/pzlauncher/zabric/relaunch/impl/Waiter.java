package com.pzlauncher.zabric.relaunch.impl;

import com.pzlauncher.zabric.internal.AtomicWrite;
import com.pzlauncher.zabric.internal.WindowsProcess;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The restart helper: its own JVM, started by the game with Zabric's jar alone on the class path, so it uses no game
 * class. It reads the launch from stdin, answers {@link #READY}, waits for the game to exit and starts it again.
 */
public final class Waiter {
    public static final String READY = "ready";
    static final Duration WAIT = Duration.ofMinutes(2);

    /** What the helper does to the world. */
    interface Effects {
        /** Whether process {@code pid} has exited, waiting up to {@code wait} for it. */
        boolean awaitExit(long pid, Duration wait) throws Exception;

        /** Starts the launch {@code spec} describes. */
        void start(LaunchSpec spec) throws Exception;

        void record(LaunchSpec spec, String reason);
    }

    private Waiter() {
    }

    public static void main(String[] args) {
        LaunchSpec spec;
        try {
            spec = LaunchSpec.read(new DataInputStream(new BufferedInputStream(System.in)));
        } catch (Exception e) {
            // no answer: the game stays and reports it
            System.err.println(RelaunchFailure.stamp("cannot read the launch from the game: " + e));
            System.exit(1);
            return;
        }
        Effects effects;
        try {
            // a Workshop update may replace the jar once the game quits
            for (Class<?> c : List.of(WindowsProcess.class, RelaunchFailure.class, AtomicWrite.class, Real.class)) {
                Class.forName(c.getName(), true, c.getClassLoader());
            }
            effects = new Real();
        } catch (Throwable t) {
            System.err.println(RelaunchFailure.stamp("cannot load the restart helper's classes: " + t));
            System.exit(1);
            return;
        }
        System.out.println(READY);
        System.out.close();
        run(spec, effects, WAIT);
    }

    static void run(LaunchSpec spec, Effects effects, Duration wait) {
        try {
            if (!effects.awaitExit(spec.pid(), wait)) {
                effects.record(spec, "the game (process " + spec.pid() + ") was still running " + wait.toMinutes()
                        + " minutes after it quit to restart");
                return;
            }
        } catch (Throwable t) {
            effects.record(spec, "cannot wait for the game (process " + spec.pid() + ") to exit: " + t);
            return;
        }
        try {
            effects.start(spec);
        } catch (Throwable t) {
            effects.record(spec, "cannot start the game again: " + t);
        }
    }

    private static final class Real implements Effects {
        @Override
        public boolean awaitExit(long pid, Duration wait) throws Exception {
            Optional<ProcessHandle> game = ProcessHandle.of(pid);
            if (game.isEmpty()) {
                return true;
            }
            try {
                game.get().onExit().get(wait.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException e) {
                return false;
            }
        }

        @Override
        public void start(LaunchSpec spec) throws Exception {
            if (!System.getProperty("os.name").startsWith("Win")) {
                throw new UnsupportedOperationException("the restart helper starts the game on Windows only");
            }
            long pid = WindowsProcess.start(spec.exe(), spec.commandLine(), spec.workingDir().toString(),
                    spec.environmentBlock());
            System.err.println(RelaunchFailure.stamp("started " + spec.exe() + " again as process " + pid));
        }

        @Override
        public void record(LaunchSpec spec, String reason) {
            RelaunchFailure.record(spec.zabricDir(), reason);
        }
    }
}
