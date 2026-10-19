package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.text.Strings;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
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
 * watermark (top left, its Java mod list as wide as the screen) and above the world-loading screen's centered and
 * bottom text. Line 1 the step with the bar by step, line 2 the item with a sub-bar for a counted one.
 */
public final class Overlay {
    private static final int MAX_WIDTH = 640;
    private static final int MARGIN = 12;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int BAR_HEIGHT = 8;
    private static final int SUB_BAR_HEIGHT = 4;
    /** The fill's grey, opaque: interim frames draw it again. */
    static final float FILL = 0.85f;

    // game thread only
    // whether this frame's UI pass draws (with the UI frame buffer, not on every frame)
    private static boolean uiPass;
    // the Java mods the loader went through this session, which the watermark lists once active, and the most a pass
    // had: the watermark lists a pass's mods before the loader goes through them
    private static final Set<String> javaMods = new TreeSet<>();
    private static int javaModsExpected;
    private static Watermark watermark;
    private static int watermarkBottom;

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
        // before drawing: a drawing error must not also read as a frame without this hook
        progress.frameHandedOver(System.nanoTime());
        if (drawn) {
            draw(view);
        }
    }

    private static void draw(Progress.View view) {
        Core core = Core.getInstance();
        int screenWidth = core.getScreenWidth();
        int width = Math.min(MAX_WIDTH, screenWidth * 2 / 5);
        int x = (screenWidth - width) / 2;
        double center = screenWidth / 2.0;
        TextManager text = TextManager.instance;
        // a Lua reset recreates the fonts and does not wait for them
        boolean fonts = text.font != null && !text.isAnyFontLoading();
        // the game's fonts have Chinese glyphs only when it runs in Chinese
        boolean chinese = "CN".equals(Translator.getLanguage().name());
        String first = Strings.stepLine(view.step(), view.flow().steps().size(), view.current(), chinese);
        String second = view.current().shows(Item.WORLD) ? worldText()
                : Strings.itemLine(view.item(), view.index(), view.total(), view.name(), chinese);
        int lineHeight = fonts ? text.getFontHeight(UIFont.Small) : 0;
        boolean secondLine = fonts && second != null;
        boolean subBar = view.sub() >= 0;
        int height = PAD + (fonts ? lineHeight + GAP : 0) + BAR_HEIGHT + (secondLine ? GAP + lineHeight : 0)
                + (subBar ? GAP + SUB_BAR_HEIGHT : 0) + PAD;
        if (fonts) {
            watermarkBottom = watermarkBottom(text, screenWidth, lineHeight);
        }
        // without fonts yet, at least below its icon
        int top = Math.max(watermarkBottom, watermarkIcon(screenWidth)) + MARGIN;

        SpriteRenderer sprites = SpriteRenderer.instance;
        sprites.renderi(null, x - PAD, top, width + 2 * PAD, height, 0f, 0f, 0f, 0.6f, null);
        int y = top + PAD;
        if (fonts) {
            text.DrawStringCentre(UIFont.Small, center, y, first, 1.0, 1.0, 1.0, 1.0);
            y += lineHeight + GAP;
        }
        bar(sprites, x, y, width, BAR_HEIGHT, (int) (width * view.fill()));
        y += BAR_HEIGHT;
        if (secondLine) {
            y += GAP;
            text.DrawStringCentre(UIFont.Small, center, y, second, 0.8, 0.8, 0.8, 1.0);
            y += lineHeight;
        }
        if (subBar) {
            y += GAP;
            int fill = (int) (width * view.sub());
            bar(sprites, x, y, width, SUB_BAR_HEIGHT, fill);
            // also between jars, which keeps the copy for the next
            if (view.item() == Item.JAVA_MOD) {
                Interim.mark(new Interim.Bar(view.jar(), x, y, width, SUB_BAR_HEIGHT, screenWidth,
                        core.getScreenHeight(), fill, (int) (width * view.jarEnd())));
            }
        }
    }

    private static void bar(SpriteRenderer sprites, int x, int y, int width, int height, int fill) {
        sprites.renderi(null, x, y, width, height, 0.3f, 0.3f, 0.3f, 1f, null);
        sprites.renderi(null, x, y, fill, height, FILL, FILL, FILL, 1f, null);
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

    private static String worldText() {
        String loading = GameLoadingState.gameLoadingString;
        return loading == null || loading.isBlank() ? null : loading;
    }
}
