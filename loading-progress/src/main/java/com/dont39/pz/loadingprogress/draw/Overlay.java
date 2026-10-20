package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import com.dont39.pz.loadingprogress.text.Strings;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.fonts.AngelCodeFont;
import zombie.gameStates.GameLoadingState;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Draws the bar at the end of every frame the game thread hands over while a flow shows: centered below ZombieBuddy's
 * watermark (top left, its Java mod list as wide as the screen), the bar by step, and under it the flow's log, newest
 * line at the bottom and older ones fading upwards, as many lines as fit above the loading screens' own text.
 */
public final class Overlay {
    private static final int MAX_WIDTH = 640;
    private static final int MARGIN = 12;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int BAR_HEIGHT = 8;
    private static final float FILL = 0.85f;
    // the alpha of the log's top line
    private static final double FADED = 0.2;

    // game thread only
    // whether this frame's UI pass draws (with the UI frame buffer, not on every frame)
    private static boolean uiPass;
    // the Java mods the loader went through this session, which the watermark lists once active, and the most a pass
    // had: the watermark lists a pass's mods before the loader goes through them
    private static final Set<String> javaMods = new TreeSet<>();
    private static int javaModsExpected;
    private static Watermark watermark;
    private static int watermarkBottom;
    private static int largeHeight;
    private static Room room;
    private static int rows;

    private Overlay() {
    }

    public static void uiPassStarted(boolean drawn) {
        uiPass = drawn;
    }

    /** Game thread: the loader goes through the jar of {@code modId}, one of {@code total} in its pass. */
    public static void javaModSeen(String modId, int total) {
        javaMods.add(modId);
        javaModsExpected = Math.max(javaModsExpected, total);
    }

    /** At the head of {@code Core.EndFrameUI}, the last point inside a frame. */
    public static void frameEnds(Progress progress) {
        boolean drawn = uiPass;
        uiPass = false;
        Progress.View view = progress.view();
        if (view == null || Thread.currentThread() != GameWindow.gameThread) {
            return;
        }
        // only here: GameLoadingState is not loaded before its screen
        if (view.current().shows(Item.WORLD)) {
            progress.worldText(GameLoadingState.gameLoadingString);
            view = progress.view();
            if (view == null) {
                return;
            }
        }
        // before drawing: a drawing error must not also read as a frame without this hook
        progress.frameHandedOver(System.nanoTime());
        if (drawn) {
            draw(view);
        }
    }

    private static void draw(Progress.View view) {
        Core core = Core.getInstance();
        int screenWidth = core.getScreenWidth();
        int screenHeight = core.getScreenHeight();
        int width = Math.min(MAX_WIDTH, screenWidth * 2 / 5);
        int x = (screenWidth - width) / 2;
        TextManager text = TextManager.instance;
        // the Small font, the only one it draws with; a Lua reset recreates the fonts and does not wait for them
        boolean fonts = text.font != null && !text.font.isLoading();
        // the game's fonts have Chinese glyphs only when it runs in Chinese
        boolean chinese = "CN".equals(Translator.getLanguage().name());
        int lineHeight = fonts ? text.getFontHeight(UIFont.Small) : 0;
        if (fonts) {
            watermarkBottom = watermarkBottom(text, screenWidth, lineHeight);
        }
        // without fonts yet, at least below its icon
        int top = Math.max(watermarkBottom, watermarkIcon(screenWidth)) + MARGIN;
        int logTop = top + PAD + BAR_HEIGHT + GAP;

        // the newest lines that fit, oldest first
        int fit = fonts ? rows(view.current(), text, screenHeight, logTop, lineHeight) : 0;
        List<Line> log = view.log();
        List<Line> shown = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0 && lines.size() < fit; i--) {
            String line = Strings.line(log.get(i), chinese);
            if (line != null) {
                shown.add(0, log.get(i));
                lines.add(0, line);
            }
        }
        int textWidth = 0;
        int newestEnd = x;
        for (String line : lines) {
            newestEnd = x + text.MeasureStringX(UIFont.Small, line);
            textWidth = Math.max(textWidth, newestEnd - x);
        }
        boolean javaMod = view.item() == Item.JAVA_MOD;
        if (javaMod && !lines.isEmpty()) {
            // the interim frames' dots stay on the box
            textWidth = Math.max(textWidth, newestEnd - x + Interim.dotsWidth(lineHeight));
        }
        int height = PAD + BAR_HEIGHT + (lines.isEmpty() ? 0 : GAP + lines.size() * lineHeight) + PAD;

        SpriteRenderer sprites = SpriteRenderer.instance;
        sprites.renderi(null, x - PAD, top, Math.max(width, textWidth) + 2 * PAD, height, 0f, 0f, 0f, 0.6f, null);
        sprites.renderi(null, x, top + PAD, width, BAR_HEIGHT, 0.3f, 0.3f, 0.3f, 1f, null);
        sprites.renderi(null, x, top + PAD, (int) (width * view.fill()), BAR_HEIGHT, FILL, FILL, FILL, 1f, null);
        int y = logTop;
        for (int i = 0; i < lines.size(); i++) {
            // by position: the top of the log is the faintest
            int age = lines.size() - 1 - i;
            double alpha = 1 - (1 - FADED) * age / Math.max(1, fit - 1);
            double grey = shown.get(i).isStep() ? 1.0 : 0.8;
            text.DrawString(UIFont.Small, x, y, lines.get(i), grey, grey, grey, alpha);
            y += lineHeight;
        }
        // also between jars, which keeps the copy for the next
        if (javaMod) {
            long jar = lines.isEmpty() ? 0 : view.jar();
            Interim.mark(new Interim.Dots(jar, newestEnd, y - lineHeight, lineHeight, screenWidth, screenHeight));
        }
    }

    /**
     * How many lines fit between the bar and the highest of the loading screens' own centered text: the epilepsy
     * warning's first line ({@code GameWindow.doEpilepsyWarningText}, 3.5 NewLarge lines above the middle). The
     * world-loading screen's text sits lower (its intro and errors around the middle, tips, prompts and the figure at
     * the bottom). One line of margin. While NewLarge loads, its line height grows as its pages do: the last loaded
     * one's counts until then, and no line shows before the first.
     */
    private static int rows(Step step, TextManager text, int screenHeight, int logTop, int lineHeight) {
        // their UI starts right under the bar
        if (step == Step.CONNECTING || step == Step.LOGIN_QUEUE) {
            return 0;
        }
        AngelCodeFont large = text.getFontFromEnum(UIFont.NewLarge);
        if (large != null && !large.isLoading()) {
            largeHeight = large.getLineHeight();
        }
        if (largeHeight == 0) {
            return 0;
        }
        Room key = new Room(screenHeight, logTop, lineHeight, largeHeight);
        if (!key.equals(room)) {
            room = key;
            int content = screenHeight / 2 - (int) (3.5f * key.largeHeight());
            rows = Math.max(0, Math.min((content - lineHeight - PAD - logTop) / lineHeight, Progress.LOG_LINES));
        }
        return rows;
    }

    // what the log's room depends on
    private record Room(int screenHeight, int logTop, int lineHeight, int largeHeight) {
    }

    /**
     * The lowest row ZombieBuddy's watermark (2.3.4, {@code Watermark.draw}) can reach: an icon, 128 px wide on
     * screens over 2000 px, else 64, and right of it its version line, a notice line after a preload change, and
     * "N active Java mods: ..." wrapped at the screen's width. The list is taken as every Java mod seen this session,
     * each marked preloaded, so the estimate errs low on the screen; it grows once a pass begins.
     */
    private static int watermarkBottom(TextManager text, int screenWidth, int lineHeight) {
        Watermark key = new Watermark(javaMods.size(), javaModsExpected, screenWidth, lineHeight);
        if (key.equals(watermark)) {
            return watermarkBottom;
        }
        watermark = key;
        int icon = watermarkIcon(screenWidth);
        int textX = icon + 4;
        int maxWidth = Math.max(80, screenWidth - textX - 4);
        // the line's prefix, then each mod after ", "; a mod that does not fit starts the next line
        int count = Math.max(javaMods.size(), javaModsExpected);
        List<Integer> widths = new ArrayList<>(count);
        for (String id : javaMods) {
            widths.add(text.MeasureStringX(UIFont.Small, id + " (preload)"));
        }
        // mods not seen yet as wide as the average one seen
        int average = widths.isEmpty() ? 0 : widths.stream().mapToInt(Integer::intValue).sum() / widths.size();
        while (widths.size() < count) {
            widths.add(average);
        }
        int lines = 1;
        int lineWidth = text.MeasureStringX(UIFont.Small, (count == 0 ? "No" : count) + " active Java mods: ");
        int separator = text.MeasureStringX(UIFont.Small, ", ");
        boolean first = true;
        for (int mod : widths) {
            if (!first && lineWidth + separator + mod > maxWidth) {
                lines++;
                lineWidth = mod;
            } else {
                lineWidth += (first ? 0 : separator) + mod;
            }
            first = false;
        }
        // its rows are as high as its version line measures
        int rowHeight = Math.max(lineHeight, text.MeasureStringY(UIFont.Small, "ZombieBuddy loaded"));
        // macOS: below the camera
        int textTop = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? 16 : 0;
        return Math.max(icon, textTop + (2 + lines) * rowHeight);
    }

    private static int watermarkIcon(int screenWidth) {
        return screenWidth > 2000 ? 128 : 64;
    }

    // what the watermark's estimate depends on
    private record Watermark(int javaMods, int javaModsExpected, int screenWidth, int lineHeight) {
    }
}
