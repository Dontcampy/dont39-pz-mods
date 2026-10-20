package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Flow;
import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import com.dont39.pz.loadingprogress.text.Strings;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.fonts.AngelCodeFont;
import zombie.gameStates.GameLoadingState;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Draws the bar at the end of every frame the game thread hands over while a flow shows, in a column at the right edge
 * ({@link Column}): below ZombieBuddy's watermark (top left, its Java mod list as wide as the screen), the bar by step,
 * and under it the flow's log, newest line at the bottom and older ones fading upwards, down to the highest of the
 * screen's own content that reaches into the column.
 */
public final class Overlay {
    private static final float FILL = 0.85f;
    // the alpha of the log's top line
    private static final double FADED = 0.2;
    // vanilla geometry (42.21): LoadingQueueUI.onresize's inset; MainScreen.lua's size for ConnectToServer
    private static final int QUEUE_INSET = 288;
    private static final int SMALL_SCREEN = 1024;

    // game thread only
    // whether this frame's UI pass draws (with the UI frame buffer, not on every frame)
    private static boolean uiPass;
    // the Java mods the loader went through this session, which the watermark lists once active, and the most a pass
    // had: the watermark lists a pass's mods before the loader goes through them
    private static final Set<String> javaMods = new TreeSet<>();
    private static int javaModsExpected;
    private static Watermark watermark;
    private static int watermarkBottom;
    // kept while a font it needs loads
    private static Measured measured;
    // cutting a line measures it about a dozen times; kept while the Small font stays
    private static final Map<Fit, String> fitted = new HashMap<>();
    private static AngelCodeFont fittedFont;

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
        TextManager text = TextManager.instance;
        // the Small font, the only one it draws with; a Lua reset recreates the fonts and does not wait for them
        boolean fonts = text.font != null && !text.font.isLoading();
        // the game's fonts have Chinese glyphs only when it runs in Chinese
        boolean chinese = "CN".equals(Translator.getLanguage().name());
        int lineHeight = fonts ? text.getFontHeight(UIFont.Small) : 0;
        if (fonts) {
            watermarkBottom = watermarkBottom(text, screenWidth, lineHeight);
        }
        // its notice and wrapped mod list can reach the right edge; without fonts yet, at least below its icon
        int top = Math.max(watermarkBottom, watermarkIcon(screenWidth)) + Column.MARGIN;
        Screen screen = Screen.of(view);
        Column column = Column.place(screenWidth, screenHeight, top, lineHeight, screen.panelRight(screenWidth),
                blocks(screen, view.current(), text, screenWidth, screenHeight));

        // the newest lines that fit, oldest first
        List<Line> log = view.log();
        List<Line> shown = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0 && lines.size() < column.rows(); i--) {
            String line = Strings.line(log.get(i), chinese);
            if (line != null) {
                shown.add(0, log.get(i));
                lines.add(0, line);
            }
        }
        boolean javaMod = view.item() == Item.JAVA_MOD;
        int x = column.textLeft();
        for (int i = 0; i < lines.size(); i++) {
            // the interim frames' dots stay in the column
            boolean dots = javaMod && i == lines.size() - 1;
            lines.set(i, fit(text, lines.get(i), column.textWidth() - (dots ? Interim.dotsWidth(lineHeight) : 0)));
        }
        int newestEnd = x + (lines.isEmpty() ? 0 : text.MeasureStringX(UIFont.Small, lines.get(lines.size() - 1)));
        int height = Column.PAD + Column.BAR_HEIGHT
                + (lines.isEmpty() ? 0 : Column.GAP + lines.size() * lineHeight) + Column.PAD;

        SpriteRenderer sprites = SpriteRenderer.instance;
        int barTop = column.top() + Column.PAD;
        int barWidth = column.textWidth();
        sprites.renderi(null, column.left(), column.top(), column.right() - column.left(), height, 0f, 0f, 0f, 0.6f,
                null);
        sprites.renderi(null, x, barTop, barWidth, Column.BAR_HEIGHT, 0.3f, 0.3f, 0.3f, 1f, null);
        sprites.renderi(null, x, barTop, (int) (barWidth * view.fill()), Column.BAR_HEIGHT, FILL, FILL, FILL, 1f,
                null);
        int y = column.logTop();
        for (int i = 0; i < lines.size(); i++) {
            // by position: the top of the log is the faintest
            int age = lines.size() - 1 - i;
            double alpha = 1 - (1 - FADED) * age / Math.max(1, column.rows() - 1);
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

    /** {@code line} as {@link Column#fit} cuts it to {@code max} in the Small font. */
    private static String fit(TextManager text, String line, int max) {
        // a Lua reset replaces the font; old lines scroll out
        if (text.font != fittedFont || fitted.size() > 2 * Progress.LOG_LINES) {
            fitted.clear();
            fittedFont = text.font;
        }
        return fitted.computeIfAbsent(new Fit(line, max),
                key -> Column.fit(line, max, cut -> text.MeasureStringX(UIFont.Small, cut)));
    }

    private record Fit(String line, int max) {
    }

    /** The vanilla screen a step's frames show. */
    private enum Screen {
        // GameWindow.doEpilepsyWarningText; the launch's own frames draw it too until the models load
        EPILEPSY,
        // ConnectToServer.lua: a centered panel 70 % of the screen wide (95 % up to 1024 px)
        CONNECT,
        // LoadingQueueUI: inset 288 px from each side
        QUEUE,
        // GameLoadingState.render; the own frames of the start and the mod reload are blank
        WORLD;

        static Screen of(Progress.View view) {
            if (view.flow() == Flow.LAUNCH) {
                return EPILEPSY;
            }
            return switch (view.current()) {
                case CONNECTING -> CONNECT;
                case LOGIN_QUEUE -> QUEUE;
                default -> WORLD;
            };
        }

        int panelRight(int screenWidth) {
            return switch (this) {
                case CONNECT -> (screenWidth + screenWidth * (screenWidth <= SMALL_SCREEN ? 95 : 70) / 100) / 2;
                case QUEUE -> screenWidth - QUEUE_INSET;
                default -> 0;
            };
        }
    }

    /** The screen's centered content as vanilla draws it; the last measured while a font it uses loads, null before. */
    private static List<Column.Block> blocks(Screen screen, Step step, TextManager text, int screenWidth,
                                             int screenHeight) {
        List<Column.Block> blocks = switch (screen) {
            case EPILEPSY -> epilepsy(text, screenWidth, screenHeight);
            case WORLD -> world(step, text, screenWidth, screenHeight);
            case CONNECT, QUEUE -> List.of();
        };
        if (blocks != null) {
            measured = new Measured(screen, screenWidth, screenHeight, blocks);
            return blocks;
        }
        return measured != null && measured.on(screen, screenWidth, screenHeight) ? measured.blocks() : null;
    }

    // eight NewLarge lines from 3.5 lines above the middle
    private static List<Column.Block> epilepsy(TextManager text, int screenWidth, int screenHeight) {
        AngelCodeFont large = text.getFontFromEnum(UIFont.NewLarge);
        if (!loaded(large)) {
            return null;
        }
        int height = large.getLineHeight();
        int y = (int) (screenHeight / 2.0f - height * 3.5f);
        List<Column.Block> blocks = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            blocks.add(centered(text, UIFont.NewLarge, screenWidth, y, translated("UI_EpilepsyWarning_" + i)));
            y += height;
        }
        return blocks;
    }

    /**
     * Around the middle the intro lines and the error messages; at the bottom the quick tips (any length), prompts
     * and MP and save-conversion messages, taken as reaching across. The walking figure (196 px, centered) stays left
     * of any column.
     */
    private static List<Column.Block> world(Step step, TextManager text, int screenWidth, int screenHeight) {
        AngelCodeFont intro = text.getFontFromEnum(UIFont.Intro);
        AngelCodeFont medium = text.getFontFromEnum(UIFont.Medium);
        AngelCodeFont small = text.getFontFromEnum(UIFont.Small);
        if (!loaded(intro) || !loaded(medium) || !loaded(small) || !loaded(text.getFontFromEnum(UIFont.NewSmall))) {
            return null;
        }
        int middle = screenHeight / 2;
        List<Column.Block> blocks = new ArrayList<>();
        int introTop = middle - intro.getLineHeight() / 2;
        for (int i = 1; i <= 3; i++) {
            blocks.add(centered(text, UIFont.Intro, screenWidth, introTop, translated("UI_Intro" + i)));
        }
        // its flag is private and its text fixed: always kept clear
        int errorTop = middle - (medium.getLineHeight() + 8 + 2 * small.getLineHeight() + 2) / 2;
        int errorLine2 = errorTop + medium.getLineHeight() + 8;
        blocks.add(centered(text, UIFont.Medium, screenWidth, errorTop,
                translated("UI_GameLoad_UnexpectedError1")));
        blocks.add(centered(text, UIFont.Small, screenWidth, errorLine2,
                translated("UI_GameLoad_UnexpectedError2")));
        blocks.add(centered(text, UIFont.Small, screenWidth, errorLine2 + small.getLineHeight() + 2,
                ZomboidFileSystem.instance.getCacheDir() + File.separator + "console.txt"));
        int lostTop = middle - (2 * medium.getLineHeight() + 2) / 2;
        if (GameWindow.serverDisconnected) {
            String reason = GameWindow.kickReason;
            blocks.add(centered(text, UIFont.Medium, screenWidth, lostTop,
                    reason != null ? reason : translated("UI_OnConnectFailed_ConnectionLost")));
        }
        // GameLoadingState is not loaded before its screen
        if ((step == Step.LOADING_WORLD || step == Step.READY) && GameLoadingState.playerWrongIP
                && GameLoadingState.gameLoadingString != null) {
            blocks.add(centered(text, UIFont.Medium, screenWidth, lostTop, GameLoadingState.gameLoadingString));
        }
        int prompts = screenHeight - 40 - 2 * text.getFontHeight(UIFont.NewSmall) - 5;
        blocks.add(new Column.Block(Math.min(prompts, screenHeight - 100), screenWidth));
        return blocks;
    }

    // as TextManager.DrawStringCentre places it; null: not translated yet
    private static Column.Block centered(TextManager text, UIFont font, int screenWidth, int top, String line) {
        int width = line == null ? 0 : text.MeasureStringX(font, line);
        return new Column.Block(top, (int) Math.ceil(screenWidth / 2.0 + width / 2.0));
    }

    // getText logs each key it lacks in debug mode, as all are in the launch's frames before the translations load
    private static String translated(String key) {
        return Translator.getTextOrNull(key);
    }

    private static boolean loaded(AngelCodeFont font) {
        return font != null && !font.isLoading();
    }

    private record Measured(Screen screen, int screenWidth, int screenHeight, List<Column.Block> blocks) {
        boolean on(Screen screen, int screenWidth, int screenHeight) {
            return this.screen == screen && this.screenWidth == screenWidth && this.screenHeight == screenHeight;
        }
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
