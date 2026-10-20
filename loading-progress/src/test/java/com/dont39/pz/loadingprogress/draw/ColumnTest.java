package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnTest {
    // 10 px per char
    private static final ToIntFunction<String> MONO = s -> 10 * s.length();

    private static Column.Block centered(int screenWidth, int top, int width) {
        return new Column.Block(top, screenWidth / 2 + width / 2);
    }

    @Test
    void takesThirtyPercentOrThirtyFourRowsAtTheRightEdge() {
        // 1080p, 1x fonts: 646 px would be more than 30 %
        Column hd = Column.place(1920, 1080, 100, 19, 0, List.of());
        assertEquals(1908, hd.right());
        assertEquals(1908 - 576, hd.left());
        // 4K, larger fonts: 34 rows of 33 px
        Column uhd = Column.place(3840, 2160, 100, 33, 0, List.of());
        assertEquals(3828 - 34 * 33, uhd.left());
        // before the Small font: 640 px, no lines
        Column early = Column.place(3840, 2160, 100, 0, 0, List.of());
        assertEquals(3828 - 640, early.left());
        assertEquals(0, early.rows());
    }

    @Test
    void runsDownToTheBottomWhenNothingReachesIt() {
        Column column = Column.place(1920, 1080, 100, 20, 0, List.of(centered(1920, 500, 600)));
        assertEquals(100 + 8 + 8 + 4, column.logTop());
        assertEquals((1080 - 12 - 8 - column.logTop()) / 20, column.rows());
    }

    @Test
    void stopsAboveTheFirstLineThatReachesIn() {
        // the epilepsy warning at 1080p: a short first line, then wide ones
        List<Column.Block> warning = List.of(centered(1920, 424, 598), centered(1920, 457, 0),
                centered(1920, 490, 1227), centered(1920, 523, 1227));
        Column column = Column.place(1920, 1080, 100, 19, 0, warning);
        assertEquals((490 - 12 - 8 - column.logTop()) / 19, column.rows());
    }

    @Test
    void keepsAMarginToContentBesideIt() {
        Column free = Column.place(1920, 1080, 100, 19, 0, List.of(new Column.Block(600, 1332 - 12)));
        Column touching = Column.place(1920, 1080, 100, 19, 0, List.of(new Column.Block(600, 1332 - 11)));
        assertEquals((1080 - 12 - 8 - free.logTop()) / 19, free.rows());
        assertEquals((600 - 12 - 8 - touching.logTop()) / 19, touching.rows());
    }

    @Test
    void movesBesideAPanel() {
        // the login queue at 1080p: the panel ends 288 px from the right
        Column queue = Column.place(1920, 1080, 100, 19, 1920 - 288, List.of());
        assertEquals(1920 - 288 + 12, queue.left());
        assertEquals((1080 - 12 - 8 - queue.logTop()) / 19, queue.rows());
        // a panel left of the column changes nothing
        Column narrow = Column.place(1920, 1080, 100, 19, 1000, List.of());
        assertEquals(1332, narrow.left());
    }

    @Test
    void showsOnlyTheBarWhereTheStripIsTooNarrow() {
        // the connect panel at 1024 px is 95 % wide
        Column connect = Column.place(1024, 768, 76, 19, (1024 + 972) / 2, List.of());
        assertEquals(0, connect.rows());
        assertEquals(1012 - 1024 * 3 / 10, connect.left());
    }

    @Test
    void showsNoLinesWhileTheContentCannotBeMeasured() {
        assertEquals(0, Column.place(1920, 1080, 100, 19, 0, null).rows());
    }

    @Test
    void neverShowsMoreThanTheLogKeeps() {
        assertEquals(Progress.LOG_LINES, Column.place(3840, 8000, 100, 19, 0, List.of()).rows());
    }

    @Test
    void neverGoesNegative() {
        assertEquals(0, Column.place(1920, 1080, 100, 19, 0, List.of(new Column.Block(50, 1920))).rows());
    }

    @Test
    void cutsLongLinesWithAnEllipsis() {
        assertEquals("Lua file 3 / 9", Column.fit("Lua file 3 / 9", 140, MONO));
        // 12 chars: 9 of the line and the ellipsis
        assertEquals("Lua file...", Column.fit("Lua file 3 / 9: media/lua/a.lua", 120, MONO));
        assertEquals("...", Column.fit("abcdef", 30, MONO));
        assertEquals("", Column.fit("abcdef", 20, MONO));
    }

    @Test
    void neverSplitsASurrogatePair() {
        String emoji = "ab😀cd";
        assertEquals("ab...", Column.fit(emoji, 50, MONO));
    }
}
