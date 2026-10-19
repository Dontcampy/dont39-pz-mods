package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.fonts.AngelCodeFont;
import zombie.core.opengl.RenderThread;
import zombie.ui.TextManager;
import zombie.ui.UIManager;

/**
 * Hands over a frame of its own while the game thread loads without drawing, so the bar moves and the render thread
 * pumps the window's messages with each frame. Called from the hooks inside the loading code, on any thread.
 */
public final class FramePump {
    // game thread only
    private static boolean pushing;
    // the epilepsy warning is on screen until the model loading's frames: own frames keep it there
    private static volatile boolean warningShown;

    private FramePump() {
    }

    public static void tick(Progress progress) {
        if (progress.ownFrames()) {
            long now = System.nanoTime();
            if (progress.ownFrameDue(now)) {
                push(progress, now);
            }
        }
    }

    /**
     * A Java mod jar starts loading: its frame is the one the render thread's interim frames start from while
     * ZombieBuddy patches the jar ({@link Progress#takeJarFrame}).
     */
    public static void jarStarts(Progress progress) {
        if (progress.ownFrames()) {
            long now = System.nanoTime();
            if (progress.takeJarFrame(now)) {
                push(progress, now);
            }
        }
    }

    private static void push(Progress progress, long now) {
        if (Thread.currentThread() != GameWindow.gameThread || pushing) {
            return;
        }
        Core core = Core.getInstance();
        // never inside a frame the game is building
        if (core.frameStage != 0) {
            return;
        }
        // ZombieBuddy's watermark draws with the Small font in every frame, as blocks while its pages load; skipped
        // only while the render thread pumps the window without frames, as in the launch's font wait
        AngelCodeFont small = TextManager.instance.font;
        if (small != null && small.isLoading() && !RenderThread.isWaitForRenderState()) {
            return;
        }
        pushing = true;
        // a frame of the UI frame buffer would show its stale contents, as GameLoadingState.render knows
        boolean uiFbo = UIManager.useUiFbo;
        UIManager.useUiFbo = false;
        try {
            progress.ownFrame();
            // without a font it returns before its frame
            if (warningShown && small != null) {
                GameWindow.doEpilepsyWarningText();
            } else {
                core.StartFrame();
                core.EndFrame();
                core.StartFrameUI();
                SpriteRenderer.instance.renderi(null, 0, 0, core.getScreenWidth(), core.getScreenHeight(), 0f, 0f, 0f,
                        1f, null);
                core.EndFrameUI();
            }
            // that hook draws the bar and times the frames, so without it own frames would come bare and unthrottled
            if (!progress.frameSince(now)) {
                throw new IllegalStateException("an own frame was handed over without running the hook on"
                        + " Core.EndFrameUI");
            }
        } finally {
            UIManager.useUiFbo = uiFbo;
            pushing = false;
        }
    }

    public static void warningShown(boolean shown) {
        warningShown = shown;
    }
}
