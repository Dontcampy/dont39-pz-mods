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

/**
 * Draws the bar at the end of every frame the game thread hands over while a flow shows: top center, clear of
 * ZombieBuddy's watermark (top left) and of the world-loading screen's centered and bottom text.
 */
public final class Overlay {
    private static final int MAX_WIDTH = 640;
    private static final int TOP = 12;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int BAR_HEIGHT = 8;
    /** The fill's grey, opaque: interim frames draw it again. */
    static final float FILL = 0.85f;

    // game thread only: whether this frame's UI pass draws (with the UI frame buffer, not on every frame)
    private static boolean uiPass;

    private Overlay() {
    }

    public static void uiPassStarted(boolean drawn) {
        uiPass = drawn;
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
        int height = PAD + (fonts ? lineHeight + GAP : 0) + BAR_HEIGHT + (secondLine ? GAP + lineHeight : 0) + PAD;

        SpriteRenderer sprites = SpriteRenderer.instance;
        sprites.renderi(null, x - PAD, TOP, width + 2 * PAD, height, 0f, 0f, 0f, 0.6f, null);
        int y = TOP + PAD;
        if (fonts) {
            text.DrawStringCentre(UIFont.Small, center, y, first, 1.0, 1.0, 1.0, 1.0);
            y += lineHeight + GAP;
        }
        int fill = (int) (width * view.fill());
        sprites.renderi(null, x, y, width, BAR_HEIGHT, 0.3f, 0.3f, 0.3f, 1f, null);
        sprites.renderi(null, x, y, fill, BAR_HEIGHT, FILL, FILL, FILL, 1f, null);
        if (view.item() == Item.JAVA_MOD) {
            Interim.mark(new Interim.Bar(view.jar(), x, y, width, BAR_HEIGHT, screenWidth, core.getScreenHeight(), fill,
                    (int) (width * view.jarEnd())));
        }
        y += BAR_HEIGHT + GAP;
        if (secondLine) {
            text.DrawStringCentre(UIFont.Small, center, y, second, 0.8, 0.8, 0.8, 1.0);
        }
    }

    private static String worldText() {
        String loading = GameLoadingState.gameLoadingString;
        return loading == null || loading.isBlank() ? null : loading;
    }
}
