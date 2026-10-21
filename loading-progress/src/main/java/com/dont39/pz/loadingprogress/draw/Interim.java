package com.dont39.pz.loadingprogress.draw;

import com.dont39.pz.loadingprogress.progress.Progress;
import com.pzlauncher.zabric.entrypoint.LoaderProgress;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.LWJGLException;
import org.lwjglx.opengl.Display;
import zombie.debug.DebugType;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureDraw;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.dont39.pz.loadingprogress.hooks.Loading.PREFIX;
import static org.lwjgl.opengl.GL11.GL_ALPHA_TEST;
import static org.lwjgl.opengl.GL11.GL_POLYGON_STIPPLE;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Frames the render thread shows while ZombieBuddy patches one Java mod jar, which holds the game thread for seconds:
 * the jar's own frame, copied from the back buffer before its swap, with one more trailing dot after the log's newest
 * line each time while Zabric's heartbeat moves (none after three), so that it stands still when the loader hangs.
 * Dots are filled rectangles: no text can be drawn here. Raw GL without direct state access (the game's drawing belongs
 * to the game thread's frame); every state it touches is read back and restored, and the game's cached state is left
 * alone. A GL error of its calls stops the mod; one the game left pending skips the frame.
 * Render thread only, except {@link #mark} and {@link #dotsWidth}; a throw stops the mod's hooks, so no frame follows.
 */
public final class Interim {
    private static final long INTERVAL = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_DOTS = 3;
    // the newest line's colour
    private static final float[] DOT = {0.8f, 0.8f, 0.8f, 1f};

    /**
     * The log's newest line in a frame that shows a Java mod jar, in screen pixels: its text ends at {@code x}, its row
     * is {@code y} to {@code y + height}. {@code jar}: {@link Progress#loadingJar()}, 0 between jars or when the line
     * is not drawn.
     */
    public record Dots(long jar, int x, int y, int height, int screenWidth, int screenHeight) {
    }

    // render thread only
    private static Dots marked;
    private static Dots shown;
    private static long lastBeat;
    private static int lastDots;
    private static long lastShown;
    private static Capture capture;
    private static Quad quad;
    private static boolean pendingLogged;
    private static final int[] framebufferWidth = new int[1];
    private static final int[] framebufferHeight = new int[1];

    private Interim() {
    }

    /** How far right of the text's end the dots reach in a row {@code height} high. */
    public static int dotsWidth(int height) {
        return 2 * MAX_DOTS * dotSize(height);
    }

    /** Game thread, inside a frame that shows a Java mod: its swap captures it. */
    public static void mark(Dots dots) {
        SpriteRenderer.instance.drawGeneric(new Mark(dots));
    }

    /** At the head of {@code Display.update(boolean)}: the back buffer holds a whole frame of the game thread's. */
    public static void frameSwaps(Progress progress) {
        if (Thread.currentThread() != RenderThread.renderThread) {
            return;
        }
        Dots dots = marked;
        if (dots == null) {
            // the log shows something else now
            if (capture != null || shown != null) {
                release();
            }
            return;
        }
        marked = null;
        lastShown = System.nanoTime();
        shown = null;
        if (dots.jar() != 0) {
            capture(dots, progress);
        }
    }

    /** At the head of {@code Display.processMessages()}: the render loop's idle path, between frames. */
    public static void idle(Progress progress) {
        if (Thread.currentThread() != RenderThread.renderThread) {
            return;
        }
        Dots dots = shown;
        if (dots == null || progress.loadingJar() != dots.jar()) {
            return;
        }
        long now = System.nanoTime();
        // a frame of the game thread's comes first
        if (now - lastShown < INTERVAL || RenderThread.isWaitForRenderState()
                || SpriteRenderer.instance.states.getReady() != null) {
            return;
        }
        long beat = LoaderProgress.heartbeat();
        if (beat == lastBeat) {
            return;
        }
        GLFW.glfwGetFramebufferSize(Display.getWindow(), framebufferWidth, framebufferHeight);
        if (framebufferWidth[0] != capture.width() || framebufferHeight[0] != capture.height()) {
            // resized or minimized since the capture: the copy would not cover the window
            shown = null;
            return;
        }
        if (gameErrorPending(progress)) {
            return;
        }
        int count = (lastDots + 1) % (MAX_DOTS + 1);
        present(dots, count);
        lastShown = now;
        lastBeat = beat;
        lastDots = count;
        progress.interimFrame(System.nanoTime());
    }

    private static void capture(Dots dots, Progress progress) {
        // the window's own size, which the screen size of the game's UI need not be
        GLFW.glfwGetFramebufferSize(Display.getWindow(), framebufferWidth, framebufferHeight);
        int w = framebufferWidth[0];
        int h = framebufferHeight[0];
        // minimized
        if (w <= 0 || h <= 0) {
            return;
        }
        if (capture == null || w != capture.width() || h != capture.height()) {
            release();
        }
        // a missing entry point would crash in native code rather than throw
        if (!GL.getCapabilities().OpenGL30) {
            throw new IllegalStateException("interim frames need OpenGL 3.0, which the game's context lacks");
        }
        if (gameErrorPending(progress)) {
            return;
        }
        Saved saved = Saved.read();
        try {
            if (capture == null) {
                capture = Capture.create(w, h);
            }
            glDisable(GL_SCISSOR_TEST);
            // a raw copy, without sRGB conversion
            glDisable(GL_FRAMEBUFFER_SRGB);
            // from a multisampled window this resolves, which needs the copy's format to match the window's
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, capture.framebuffer());
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        } finally {
            saved.restore();
        }
        check("copying a frame for interim frames");
        shown = dots;
        lastBeat = LoaderProgress.heartbeat();
        lastDots = 0;
    }

    private static void present(Dots dots, int count) {
        int width = capture.width();
        int height = capture.height();
        double sx = (double) width / dots.screenWidth();
        double sy = (double) height / dots.screenHeight();
        int size = dotSize(dots.height());
        int bottom = dots.y() + dots.height() * 3 / 4;
        Saved saved = Saved.read();
        try {
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            if (capture.multisampled()) {
                // a blit cannot write a multisampled window: draw the copy
                if (quad == null) {
                    quad = Quad.create();
                }
                quad.draw(capture);
            } else {
                glDisable(GL_FRAMEBUFFER_SRGB);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, capture.framebuffer());
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            }
            // the dots are written as the game writes its text
            saved.srgbWrites();
            glEnable(GL_SCISSOR_TEST);
            for (int i = 0; i < count; i++) {
                int left = dots.x() + size + i * 2 * size;
                int x0 = (int) Math.round(left * sx);
                int x1 = (int) Math.round((left + size) * sx);
                int y0 = (int) Math.round((bottom - size) * sy);
                int y1 = (int) Math.round(bottom * sy);
                // GL counts rows from the bottom
                glScissor(x0, height - y1, x1 - x0, y1 - y0);
                glClearBufferfv(GL_COLOR, 0, DOT);
            }
        } finally {
            saved.restore();
        }
        check("drawing an interim frame");
        try {
            // glfwSwapBuffers only: no other mod's advice on Display.update runs for a frame that is not the game's
            Display.swapBuffers();
        } catch (LWJGLException e) {
            throw new IllegalStateException("cannot swap an interim frame", e);
        }
    }

    private static void release() {
        shown = null;
        if (capture != null) {
            glDeleteFramebuffers(capture.framebuffer());
            glDeleteTextures(capture.texture());
            capture = null;
        }
    }

    // after gameErrorPending, so an error is our calls'
    private static void check(String what) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(error) + " after " + what);
        }
    }

    /**
     * Before any GL call of ours: an error already pending is the game's. It skips this frame or copy, not the mod; the
     * first is logged with every flag it cleared, the rest are counted in the flow's line.
     */
    private static boolean gameErrorPending(Progress progress) {
        int error = glGetError();
        if (error == GL_NO_ERROR) {
            return false;
        }
        StringBuilder errors = new StringBuilder("0x").append(Integer.toHexString(error));
        // one flag per kind of error; bounded, as a lost context keeps reporting one
        for (int i = 0; i < 8 && (error = glGetError()) != GL_NO_ERROR; i++) {
            errors.append(", 0x").append(Integer.toHexString(error));
        }
        progress.interimSkipped();
        if (!pendingLogged) {
            pendingLogged = true;
            DebugType.Mod.println(PREFIX + "OpenGL errors left pending by the game before an interim frame, cleared"
                    + " and skipped: %s; the flow's line counts later skips", errors.toString());
        }
        return true;
    }

    /** A frame-sized texture in the window's color format, with a frame buffer to copy into it. */
    private record Capture(int framebuffer, int texture, int width, int height, boolean multisampled, boolean srgb) {
        /** With the window's frame buffer bound. */
        static Capture create(int w, int h) {
            boolean multisampled = glGetInteger(GL_SAMPLE_BUFFERS) != 0;
            int format = windowFormat();
            boolean srgb = format == GL_SRGB8 || format == GL_SRGB8_ALPHA8;
            int boundTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int texture = glGenTextures();
            try {
                glBindTexture(GL_TEXTURE_2D, texture);
                // no pixel data: a bound unpack buffer would be read instead
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexImage2D(GL_TEXTURE_2D, 0, format, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            } finally {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
                glBindTexture(GL_TEXTURE_2D, boundTexture);
            }
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            int status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                glDeleteFramebuffers(framebuffer);
                glDeleteTextures(texture);
                throw new IllegalStateException("the " + w + "x" + h + " frame buffer for interim frames is"
                        + " incomplete: status 0x" + Integer.toHexString(status));
            }
            return new Capture(framebuffer, texture, w, h, multisampled, srgb);
        }

        // the game asks for 8 bits per color and no alpha (Core's PixelFormat); drivers may add alpha
        private static int windowFormat() {
            int red = attachment(GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE);
            int green = attachment(GL_FRAMEBUFFER_ATTACHMENT_GREEN_SIZE);
            int blue = attachment(GL_FRAMEBUFFER_ATTACHMENT_BLUE_SIZE);
            int alpha = attachment(GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
            boolean srgb = attachment(GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING) == GL_SRGB;
            if (red == 8 && green == 8 && blue == 8 && (alpha == 0 || alpha == 8)) {
                return alpha == 0 ? (srgb ? GL_SRGB8 : GL_RGB8) : (srgb ? GL_SRGB8_ALPHA8 : GL_RGBA8);
            }
            if (red == 10 && green == 10 && blue == 10 && alpha == 2 && !srgb) {
                return GL_RGB10_A2;
            }
            throw new IllegalStateException("the game window's color buffer has " + red + "/" + green + "/" + blue
                    + "/" + alpha + " bits" + (srgb ? " (sRGB)" : "") + ", not the 8 bits per color the game asks for");
        }

        private static int attachment(int name) {
            return glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_BACK_LEFT, name);
        }
    }

    /**
     * Draws the copy over a multisampled window with a triangle that covers it, each fragment fetching its own pixel.
     * Everything that decides what a fragment writes is read back, set for the draw and restored.
     */
    private record Quad(int program, int vertexArray, int buffer, boolean compatibility, boolean samplers,
                        int clipDistances, int[] switches) {
        // after the version line: 130 runs on a 3.0 context, and a core profile need not accept it
        private static final String VERTEX = """
                in vec2 position;
                void main() {
                    gl_Position = vec4(position, 0.0, 1.0);
                }
                """;
        private static final String FRAGMENT = """
                uniform sampler2D frame;
                out vec4 color;
                void main() {
                    color = texelFetch(frame, ivec2(gl_FragCoord.xy), 0);
                }
                """;

        static Quad create() {
            GLCapabilities caps = GL.getCapabilities();
            // without a profile (before 3.2) a context has the fixed-function state too
            boolean compatibility = !caps.OpenGL32
                    || (glGetInteger(GL_CONTEXT_PROFILE_MASK) & GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0;
            int program = link(compatibility ? "#version 130\n" : "#version 150\n");
            int boundArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int boundBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            int vertexArray = glGenVertexArrays();
            int buffer = glGenBuffers();
            try {
                glBindVertexArray(vertexArray);
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
                glBufferData(GL_ARRAY_BUFFER, new float[]{-1f, -1f, 3f, -1f, -1f, 3f}, GL_STATIC_DRAW);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
            } finally {
                glBindVertexArray(boundArray);
                glBindBuffer(GL_ARRAY_BUFFER, boundBuffer);
            }
            List<Integer> switches = new ArrayList<>(List.of(GL_BLEND, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_CULL_FACE,
                    GL_COLOR_LOGIC_OP, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE));
            if (caps.OpenGL32) {
                switches.add(GL_SAMPLE_MASK);
            }
            if (compatibility) {
                switches.addAll(List.of(GL_ALPHA_TEST, GL_POLYGON_STIPPLE));
            }
            return new Quad(program, vertexArray, buffer, compatibility,
                    caps.OpenGL33 || caps.GL_ARB_sampler_objects, glGetInteger(GL_MAX_CLIP_DISTANCES),
                    switches.stream().mapToInt(Integer::intValue).toArray());
        }

        void draw(Capture capture) {
            int boundProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int boundArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int boundTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            int boundSampler = samplers ? glGetInteger(GL_SAMPLER_BINDING) : 0;
            int[] viewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            int[] polygonMode = new int[2];
            glGetIntegerv(GL_POLYGON_MODE, polygonMode);
            boolean[] enabled = new boolean[switches.length + clipDistances];
            for (int i = 0; i < enabled.length; i++) {
                int cap = i < switches.length ? switches[i] : GL_CLIP_DISTANCE0 + i - switches.length;
                enabled[i] = glIsEnabled(cap);
                glDisable(cap);
            }
            try {
                glUseProgram(program);
                glBindVertexArray(vertexArray);
                glBindTexture(GL_TEXTURE_2D, capture.texture());
                if (samplers) {
                    glBindSampler(0, 0);
                }
                glViewport(0, 0, capture.width(), capture.height());
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                // an sRGB copy reads decoded, so it is encoded again on the way out
                if (capture.srgb()) {
                    glEnable(GL_FRAMEBUFFER_SRGB);
                } else {
                    glDisable(GL_FRAMEBUFFER_SRGB);
                }
                glDrawArrays(GL_TRIANGLES, 0, 3);
            } finally {
                for (int i = 0; i < enabled.length; i++) {
                    if (enabled[i]) {
                        glEnable(i < switches.length ? switches[i] : GL_CLIP_DISTANCE0 + i - switches.length);
                    }
                }
                if (compatibility) {
                    glPolygonMode(GL_FRONT, polygonMode[0]);
                    glPolygonMode(GL_BACK, polygonMode[1]);
                } else {
                    glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0]);
                }
                glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                if (samplers) {
                    glBindSampler(0, boundSampler);
                }
                glBindTexture(GL_TEXTURE_2D, boundTexture);
                glActiveTexture(activeTexture);
                glBindVertexArray(boundArray);
                glUseProgram(boundProgram);
            }
        }

        private static int link(String version) {
            int vertex = shader(GL_VERTEX_SHADER, version + VERTEX);
            int fragment;
            try {
                fragment = shader(GL_FRAGMENT_SHADER, version + FRAGMENT);
            } catch (RuntimeException e) {
                glDeleteShader(vertex);
                throw e;
            }
            int program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glBindAttribLocation(program, 0, "position");
            glBindFragDataLocation(program, 0, "color");
            glLinkProgram(program);
            glDetachShader(program, vertex);
            glDetachShader(program, fragment);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new IllegalStateException("cannot link the program for interim frames: " + log);
            }
            return program;
        }

        private static int shader(int type, String source) {
            int shader = glCreateShader(type);
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new IllegalStateException("cannot compile a shader for interim frames: " + log);
            }
            return shader;
        }
    }

    /**
     * The GL state the copies and the clear depend on, read back so that it can be written back exactly. The window's
     * frame buffer is bound while they run, so its read and draw buffers can be read and set to the back buffer.
     */
    private record Saved(int readFramebuffer, int drawFramebuffer, int readBuffer, int drawBuffer, boolean scissorTest,
                         int boxX, int boxY, int boxWidth, int boxHeight, boolean red, boolean green, boolean blue,
                         boolean alpha, boolean discard, boolean srgb) {
        private static final int[] box = new int[4];
        private static final int[] mask = new int[4];

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
            boolean discard = glIsEnabled(GL_RASTERIZER_DISCARD);
            // it would drop the clear
            glDisable(GL_RASTERIZER_DISCARD);
            return new Saved(readFramebuffer, drawFramebuffer, readBuffer, drawBuffer, glIsEnabled(GL_SCISSOR_TEST),
                    box[0], box[1], box[2], box[3], mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0, discard,
                    glIsEnabled(GL_FRAMEBUFFER_SRGB));
        }

        void srgbWrites() {
            if (srgb) {
                glEnable(GL_FRAMEBUFFER_SRGB);
            } else {
                glDisable(GL_FRAMEBUFFER_SRGB);
            }
        }

        void restore() {
            srgbWrites();
            if (discard) {
                glEnable(GL_RASTERIZER_DISCARD);
            }
            glColorMask(red, green, blue, alpha);
            glScissor(boxX, boxY, boxWidth, boxHeight);
            if (scissorTest) {
                glEnable(GL_SCISSOR_TEST);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            // the window's own read and draw buffers, so with its frame buffer bound
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glReadBuffer(readBuffer);
            glDrawBuffer(drawBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
    }

    // square dots near the baseline, an eighth of the row high and as far apart
    private static int dotSize(int height) {
        return Math.max(2, height / 8);
    }

    // runs on the render thread where the frame's UI ends, just before that frame's swap
    private static final class Mark extends TextureDraw.GenericDrawer {
        private final Dots dots;

        Mark(Dots dots) {
            this.dots = dots;
        }

        @Override
        public void render() {
            marked = dots;
        }
    }
}
