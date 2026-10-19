package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;
import com.pzlauncher.zabric.entrypoint.LoaderProgress;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.LWJGLException;
import org.lwjglx.opengl.Display;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugType;

import java.util.concurrent.TimeUnit;

import static com.dont39.pz.loadingprogress.hooks.Loading.PREFIX;
import static org.lwjgl.opengl.GL45C.GL_BACK;
import static org.lwjgl.opengl.GL45C.GL_COLOR;
import static org.lwjgl.opengl.GL45C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL45C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL45C.GL_DRAW_BUFFER;
import static org.lwjgl.opengl.GL45C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL45C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL45C.GL_NEAREST;
import static org.lwjgl.opengl.GL45C.GL_READ_BUFFER;
import static org.lwjgl.opengl.GL45C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL45C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL45C.GL_RGBA8;
import static org.lwjgl.opengl.GL45C.GL_SAMPLE_BUFFERS;
import static org.lwjgl.opengl.GL45C.GL_SCISSOR_BOX;
import static org.lwjgl.opengl.GL45C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL45C.glBindFramebuffer;
import static org.lwjgl.opengl.GL45C.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL45C.glClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL45C.glColorMask;
import static org.lwjgl.opengl.GL45C.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45C.glCreateRenderbuffers;
import static org.lwjgl.opengl.GL45C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45C.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL45C.glDisable;
import static org.lwjgl.opengl.GL45C.glDrawBuffer;
import static org.lwjgl.opengl.GL45C.glEnable;
import static org.lwjgl.opengl.GL45C.glGetInteger;
import static org.lwjgl.opengl.GL45C.glGetIntegerv;
import static org.lwjgl.opengl.GL45C.glIsEnabled;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL45C.glNamedRenderbufferStorage;
import static org.lwjgl.opengl.GL45C.glReadBuffer;
import static org.lwjgl.opengl.GL45C.glScissor;

/**
 * Frames the render thread shows while ZombieBuddy patches one Java mod jar, which holds the game thread for seconds:
 * the jar's own frame, copied from the back buffer before its swap, with the bar filled a pixel further into the jar's
 * share each time while Zabric's heartbeat moves, so that it stands still when the loader hangs. The text stays as
 * captured. Raw GL only (the game's drawing belongs to the game thread's frame); every state it touches is read back
 * and restored, and the game's cached state is left alone.
 * Render thread only, except {@link #mark}; a throw stops the mod's hooks, so no frame follows.
 */
public final class Interim {
    // a jar's share of the bar is a few pixels wide: one pixel per frame, spaced out so that they last
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(750);
    private static final float[] FILL = {Overlay.FILL, Overlay.FILL, Overlay.FILL, 1f};

    /**
     * The bar of a frame that shows a Java mod jar, in screen pixels: {@code fill} wide, and {@code limit} once the jar
     * loaded. {@code jar}: {@link Progress#loadingJar()}, 0 between jars.
     */
    public record Bar(long jar, int x, int y, int width, int height, int screenWidth, int screenHeight, int fill,
                      int limit) {
    }

    // render thread only
    private static Bar marked;
    private static Bar shown;
    private static long lastBeat;
    private static int lastFill;
    private static long lastShown;
    private static int framebuffer;
    private static int renderbuffer;
    private static int width;
    private static int height;
    private static boolean checked;
    private static boolean unavailable;
    private static final int[] framebufferWidth = new int[1];
    private static final int[] framebufferHeight = new int[1];
    private static final int[] box = new int[4];
    private static final int[] mask = new int[4];

    private Interim() {
    }

    /** Game thread, inside a frame that shows a Java mod jar: its swap captures it. */
    public static void mark(Bar bar) {
        SpriteRenderer.instance.drawGeneric(new Mark(bar));
    }

    /** At the head of {@code Display.update(boolean)}: the back buffer holds a whole frame of the game thread's. */
    public static void frameSwaps() {
        if (Thread.currentThread() != RenderThread.renderThread) {
            return;
        }
        Bar bar = marked;
        if (bar == null) {
            // the bar shows something else now
            if (framebuffer != 0 || shown != null) {
                release();
            }
            return;
        }
        marked = null;
        lastShown = System.nanoTime();
        shown = null;
        if (bar.jar() == 0 || !available()) {
            return;
        }
        capture(bar);
    }

    /** At the head of {@code Display.processMessages()}: the render loop's idle path, between frames. */
    public static void idle(Progress progress) {
        if (Thread.currentThread() != RenderThread.renderThread) {
            return;
        }
        Bar bar = shown;
        if (bar == null || progress.loadingJar() != bar.jar()) {
            return;
        }
        long now = System.nanoTime();
        // a frame of the game thread's comes first
        if (now - lastShown < INTERVAL || RenderThread.isWaitForRenderState()
                || SpriteRenderer.instance.states.getReady() != null) {
            return;
        }
        long beat = LoaderProgress.heartbeat();
        // short of the limit, where the next jar's own frame takes over
        int fill = lastFill + 1;
        if (beat == lastBeat || fill >= bar.limit()) {
            return;
        }
        GLFW.glfwGetFramebufferSize(Display.getWindow(), framebufferWidth, framebufferHeight);
        if (framebufferWidth[0] != width || framebufferHeight[0] != height) {
            // resized or minimized since the capture: the copy would not cover the window
            shown = null;
            return;
        }
        present(bar, fill);
        lastShown = now;
        lastBeat = beat;
        lastFill = fill;
        progress.interimFrame(System.nanoTime());
    }

    private static boolean available() {
        if (!checked) {
            checked = true;
            GLCapabilities caps = GL.getCapabilities();
            String why = caps.OpenGL45 || caps.GL_ARB_direct_state_access ? null
                    : "its OpenGL context has no direct state access (OpenGL 4.5)";
            if (why == null) {
                Saved saved = Saved.read();
                try {
                    if (glGetInteger(GL_SAMPLE_BUFFERS) != 0) {
                        why = "the game window is multisampled";
                    }
                } finally {
                    saved.restore();
                }
            }
            if (why != null) {
                unavailable = true;
                DebugType.Mod.warn(PREFIX + "no frames during a Java mod's patching this session: %s; the bar moves at"
                        + " each Java mod only", why);
            }
        }
        return !unavailable;
    }

    private static void capture(Bar bar) {
        // the window's own size, which the screen size of the game's UI need not be
        GLFW.glfwGetFramebufferSize(Display.getWindow(), framebufferWidth, framebufferHeight);
        int w = framebufferWidth[0];
        int h = framebufferHeight[0];
        // minimized
        if (w <= 0 || h <= 0) {
            return;
        }
        if (framebuffer == 0 || w != width || h != height) {
            release();
            create(w, h);
        }
        Saved saved = Saved.read();
        try {
            glDisable(GL_SCISSOR_TEST);
            glBlitNamedFramebuffer(0, framebuffer, 0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        } finally {
            saved.restore();
        }
        shown = bar;
        lastBeat = LoaderProgress.heartbeat();
        lastFill = bar.fill();
    }

    private static void present(Bar bar, int fill) {
        double sx = (double) width / bar.screenWidth();
        double sy = (double) height / bar.screenHeight();
        int left = (int) Math.round((bar.x() + bar.fill()) * sx);
        int right = (int) Math.round((bar.x() + fill) * sx);
        int top = (int) Math.round(bar.y() * sy);
        int bottom = (int) Math.round((bar.y() + bar.height()) * sy);
        Saved saved = Saved.read();
        try {
            glDisable(GL_SCISSOR_TEST);
            glBlitNamedFramebuffer(framebuffer, 0, 0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT,
                    GL_NEAREST);
            glEnable(GL_SCISSOR_TEST);
            // GL counts rows from the bottom
            glScissor(left, height - bottom, right - left, bottom - top);
            glColorMask(true, true, true, true);
            glClearNamedFramebufferfv(0, GL_COLOR, 0, FILL);
        } finally {
            saved.restore();
        }
        try {
            // glfwSwapBuffers only: no other mod's advice on Display.update runs for a frame that is not the game's
            Display.swapBuffers();
        } catch (LWJGLException e) {
            throw new IllegalStateException("cannot swap an interim frame", e);
        }
    }

    private static void create(int w, int h) {
        renderbuffer = glCreateRenderbuffers();
        glNamedRenderbufferStorage(renderbuffer, GL_RGBA8, w, h);
        framebuffer = glCreateFramebuffers();
        glNamedFramebufferRenderbuffer(framebuffer, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
        width = w;
        height = h;
        int status = glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("the " + w + "x" + h + " frame buffer for interim frames is incomplete:"
                    + " status 0x" + Integer.toHexString(status));
        }
    }

    private static void release() {
        shown = null;
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (renderbuffer != 0) {
            glDeleteRenderbuffers(renderbuffer);
            renderbuffer = 0;
        }
    }

    /**
     * The GL state the copies and the clear depend on, read back so that it can be written back exactly. The window's
     * frame buffer is bound while they run, so its read and draw buffers can be read and set to the back buffer.
     */
    private record Saved(int readFramebuffer, int drawFramebuffer, int readBuffer, int drawBuffer, boolean scissorTest,
                         int boxX, int boxY, int boxWidth, int boxHeight, boolean red, boolean green, boolean blue,
                         boolean alpha) {
        static Saved read() {
            int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            int readBuffer = glGetInteger(GL_READ_BUFFER);
            int drawBuffer = glGetInteger(GL_DRAW_BUFFER);
            glReadBuffer(GL_BACK);
            glDrawBuffer(GL_BACK);
            glGetIntegerv(GL_SCISSOR_BOX, box);
            glGetIntegerv(GL_COLOR_WRITEMASK, mask);
            return new Saved(readFramebuffer, drawFramebuffer, readBuffer, drawBuffer, glIsEnabled(GL_SCISSOR_TEST),
                    box[0], box[1], box[2], box[3], mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
        }

        void restore() {
            glColorMask(red, green, blue, alpha);
            glScissor(boxX, boxY, boxWidth, boxHeight);
            if (scissorTest) {
                glEnable(GL_SCISSOR_TEST);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            glReadBuffer(readBuffer);
            glDrawBuffer(drawBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
    }

    // runs on the render thread where the frame's UI ends, just before that frame's swap
    private static final class Mark extends TextureDraw.GenericDrawer {
        private final Bar bar;

        Mark(Bar bar) {
            this.bar = bar;
        }

        @Override
        public void render() {
            marked = bar;
        }
    }
}
