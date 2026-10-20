package com.dont39.pz.loadingprogress.text;

import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Known;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringsTest {
    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void gapNamesItsKnownCalls() {
        Progress.Gap gap = new Progress.Gap(ms(1200), Step.SCRIPTS, new Progress.Shown(Item.SCRIPT, 3, 9, "a.txt"),
                Known.COMMENT_STRIPPING, ms(800));
        assertEquals("1200 ms in Loading scripts (Script file 3 / 9: a.txt), 800 ms of it in known calls, mostly script"
                + " comment stripping", Strings.gap(gap));
    }

    @Test
    void gapLeavesOutKnownCallsUnderAMillisecond() {
        Progress.Gap gap = new Progress.Gap(ms(1200), Step.TILES, null, Known.COMMENT_STRIPPING, ms(1) - 1);
        assertEquals("1200 ms in Loading tiles and furniture", Strings.gap(gap));
    }

    @Test
    void gapWithoutKnownCalls() {
        Progress.Gap gap = new Progress.Gap(ms(40), Step.MODS, null, null, 0);
        assertEquals("40 ms in Loading mods", Strings.gap(gap));
    }
}
