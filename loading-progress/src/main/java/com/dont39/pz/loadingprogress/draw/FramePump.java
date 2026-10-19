package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
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
        if (!progress.ownFrames()) {
            return;
        }
        long now = System.nanoTime();
        if (!progress.ownFrameDue(now) || Thread.currentThread() != GameWindow.gameThread || pushing) {
            return;
        }
        Core core = Core.getInstance();
        // never inside a frame the game is building
        if (core.frameStage != 0) {
            return;
        }
        pushing = true;
        // a frame of the UI frame buffer would show its stale contents, as GameLoadingState.render knows
        boolean uiFbo = UIManager.useUiFbo;
        UIManager.useUiFbo = false;
        try {
            progress.ownFrame();
            if (warningShown) {
                GameWindow.doEpilepsyWarningText();
            } else {
                core.StartFrame();
                core.EndFrame();
                core.StartFrameUI();
                SpriteRenderer.instance.renderi(null, 0, 0, core.getScreenWidth(), core.getScreenHeight(), 0f, 0f, 0f,
                        1f, null);
                core.EndFrameUI();
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
