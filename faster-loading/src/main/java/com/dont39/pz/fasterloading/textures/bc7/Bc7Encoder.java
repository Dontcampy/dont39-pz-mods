package com.dont39.pz.fasterloading.textures.bc7;

import com.dont39.pz.fasterloading.textures.Blocks;
import com.dont39.pz.fasterloading.textures.Encoder;
import com.dont39.pz.fasterloading.textures.Upload;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * BC7 on the GPU: DirectXTex's compute shader ({@code BC7Encode.comp}), modes 4-7. The render thread only uploads the
 * game's pixels, dispatches the passes and fills the level from the GPU's output; none of these calls waits for the GPU.
 */
public final class Bc7Encoder implements Encoder {
    /** The most work groups a dispatch has in each dimension: the GL's guaranteed minimum. */
    static final int MAX_GROUPS = 65535;
    // binding points of the shader's buffers
    static final int INPUT = 0;
    static final int TRIED = 1;
    static final int OUTPUT = 2;
    // a level's input is still read by the GPU while the next ones upload; a ring beat orphaning one buffer
    private static final int RING = 8;
    // the buffers fit a tile page's level and a larger level goes through them in strips of whole block rows: buffers
    // grown to the largest level held 640 MB of video memory after eight 4096² levels, and buffers of its own per large
    // level still left ~110 MB in the driver's cache
    static final int STRIP_BLOCKS = 1024 * 1024 / 16;
    private static final long INPUT_BYTES = 16 + STRIP_BLOCKS * 16L * 4;
    private static final long BLOCK_BYTES = STRIP_BLOCKS * 16L;
    private static final String SOURCE = "BC7Encode.comp";
    private static final int[] MODES_137 = {1, 3, 7};

    private final int try456;
    private final int[] try137 = new int[MODES_137.length];
    private final int encode;
    private final int[] inputs = new int[RING];
    private int next;
    private final int triedA;
    private final int triedB;
    private final int output;
    // the mipmapped texture whose chain this allocated, while its levels arrive in order
    private Chain chain;

    /**
     * Builds the programs and buffers on the GL thread with the context current; throws with the GL's log when a
     * program does not compile or link, and when the GL reports an error.
     */
    public Bc7Encoder() {
        String body = source();
        Upload.clearErrors();
        try456 = program("ENTRY_456", "", body);
        for (int k = 0; k < MODES_137.length; k++) {
            try137[k] = program("ENTRY_137", "#define g_mode_id " + MODES_137[k] + "u\n", body);
        }
        encode = program("ENTRY_ENCODE", "", body);
        GL15.glGenBuffers(inputs);
        for (int input : inputs) {
            storage(input, INPUT_BYTES, GL15.GL_STREAM_DRAW);
        }
        triedA = storage(GL15.glGenBuffers(), BLOCK_BYTES, GL15.GL_DYNAMIC_COPY);
        triedB = storage(GL15.glGenBuffers(), BLOCK_BYTES, GL15.GL_DYNAMIC_COPY);
        output = storage(GL15.glGenBuffers(), BLOCK_BYTES, GL15.GL_DYNAMIC_COPY);
        Upload.checkErrors(() -> "while building " + id());
    }

    @Override
    public int glInternalFormat() {
        return GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;
    }

    @Override
    public String id() {
        return "bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2";
    }

    @Override
    public void define(int target, int level, int width, int height, ByteBuffer rgba) {
        int rows = stripRows(width, height);
        GlState saved = GlState.save(INPUT, TRIED, OUTPUT);
        Throwable failure = null;
        try {
            issue(target, level, width, height, rows, rgba);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            saved.restore(failure);
        }
    }

    private void issue(int target, int level, int width, int height, int rows, ByteBuffer rgba) {
        // NVIDIA's driver reallocates a texture's storage when a level is added, after the GPU writes pending into it:
        // a level defined from the GPU's output per call kept the render thread waiting for the GPU (10 ms a page).
        // The game defines every level next, so a mipmapped texture's chain is allocated at its first level and each
        // level filled.
        int texture = target == GL11.GL_TEXTURE_2D ? GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) : 0;
        Chain next = chain;
        chain = null;
        // the next level of the chain allocated here skips three queries, each a wait for the driver's thread
        if (next == null || !next.holds(texture, level, width, height)) {
            next = null;
            if (!allocated(target, level, width, height)) {
                boolean mipmapped = mipmapped(target);
                allocate(target, level, width, height, mipmapped);
                if (mipmapped && texture != 0) {
                    next = new Chain(texture, level, width, height);
                }
            }
        }
        for (int y = 0; y < height; y += rows) {
            strip(target, level, y, width, Math.min(rows, height - y), rgba);
        }
        chain = next == null ? null : next.after();
    }

    // rows [y, y + height) of the level; y is a multiple of 4, so the strip's blocks are the level's
    private void strip(int target, int level, int y, int width, int height, ByteBuffer rgba) {
        int columns = Blocks.columns(width);
        int blocks = Blocks.count(width, height);
        int input = inputs[next];
        next = (next + 1) % RING;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, input);
        // the shader's sizes come with the pixels: a uniform update would wait for dispatches in flight
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, new int[] {width, height, columns, blocks});
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 16,
                rgba.slice(rgba.position() + y * width * 4, width * height * 4));
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INPUT, input);

        Groups fours = groups((blocks + 3) / 4);
        Groups ones = groups(blocks);
        // each pass keeps the better of its own try and the one before, per block, and the next reads it as storage
        dispatch(try456, triedB, triedA, fours, GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        int tried = triedA;
        for (int program : try137) {
            int better = tried == triedA ? triedB : triedA;
            dispatch(program, tried, better, ones, GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            tried = better;
        }
        // only the fill reads the output; the next pass to write a buffer this one reads or writes follows a try
        // pass's barrier
        dispatch(encode, tried, output, fours, GL42.GL_PIXEL_BUFFER_BARRIER_BIT);

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, output);
        GL13.glCompressedTexSubImage2D(target, level, 0, y, width, height, glInternalFormat(), blocks * 16, 0L);
    }

    private static int storage(int buffer, long bytes, int usage) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, usage);
        return buffer;
    }

    private boolean allocated(int target, int level, int width, int height) {
        return GL11.glGetTexLevelParameteri(target, level, GL11.GL_TEXTURE_INTERNAL_FORMAT) == glInternalFormat()
                && GL11.glGetTexLevelParameteri(target, level, GL11.GL_TEXTURE_WIDTH) == width
                && GL11.glGetTexLevelParameteri(target, level, GL11.GL_TEXTURE_HEIGHT) == height;
    }

    // the game sets the filter before the upload and sends level 0 alone when it does not mipmap: a chain there would
    // cost a third more video memory than the driver's DXT5
    private static boolean mipmapped(int target) {
        int filter = GL11.glGetTexParameteri(target, GL11.GL_TEXTURE_MIN_FILTER);
        return filter != GL11.GL_NEAREST && filter != GL11.GL_LINEAR;
    }

    // the level, with chain every smaller one down to 1x1, without data; no pixel unpack buffer is bound
    private void allocate(int target, int level, int width, int height, boolean chain) {
        while (true) {
            GL13.glCompressedTexImage2D(target, level, glInternalFormat(), width, height, 0,
                    Blocks.count(width, height) * 16, 0L);
            if (!chain || width == 1 && height == 1) {
                return;
            }
            level++;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
    }

    // barrier: what the next reader of out needs
    private static void dispatch(int program, int in, int out, Groups groups, int barrier) {
        GL20.glUseProgram(program);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, TRIED, in);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, OUTPUT, out);
        GL43.glDispatchCompute(groups.x(), groups.y(), 1);
        GL42.glMemoryBarrier(barrier);
    }

    /**
     * The rows of a level's strips: whole block rows, at most {@link #STRIP_BLOCKS} blocks (and so at most as many
     * pixels as 16 times that) a strip; the last strip may be shorter.
     */
    public static int stripRows(int width, int height) {
        int columns = Blocks.columns(width);
        if (columns > STRIP_BLOCKS) {
            throw new IllegalArgumentException("a " + width
                    + " pixels wide level has more blocks a row than a strip holds");
        }
        int blockRows = STRIP_BLOCKS / columns;
        return (int) Math.min(height, blockRows * 4L);
    }

    /**
     * The grid for {@code count} work groups, which the shader numbers row by row: as few rows as fit, each as short
     * as covers the count, so fewer groups than rows lie past it.
     */
    static Groups groups(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("groups " + count);
        }
        int y = (count + MAX_GROUPS - 1) / MAX_GROUPS;
        if (y > MAX_GROUPS) {
            throw new IllegalArgumentException("groups " + count + " exceed " + MAX_GROUPS + " x " + MAX_GROUPS);
        }
        return new Groups((count + y - 1) / y, y);
    }

    record Groups(int x, int y) {
    }

    // a texture's allocated chain, at the level expected next
    record Chain(int texture, int level, int width, int height) {
        boolean holds(int texture, int level, int width, int height) {
            return texture == this.texture && level == this.level && width == this.width && height == this.height;
        }

        Chain after() {
            return width == 1 && height == 1 ? null
                    : new Chain(texture, level + 1, Math.max(1, width / 2), Math.max(1, height / 2));
        }
    }

    private static String source() {
        try (InputStream in = Bc7Encoder.class.getResourceAsStream(SOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SOURCE + " is not in the jar next to " + Bc7Encoder.class.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SOURCE, e);
        }
    }

    private static int program(String entry, String defines, String body) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        try {
            GL20.glShaderSource(shader, "#version 430\n#define " + entry + "\n" + defines + body);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(SOURCE + " " + entry + " " + defines.strip() + " does not compile: "
                        + GL20.glGetShaderInfoLog(shader));
            }
            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, shader);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(SOURCE + " " + entry + " " + defines.strip() + " does not link: "
                        + GL20.glGetProgramInfoLog(program));
            }
            GL20.glDetachShader(program, shader);
            return program;
        } finally {
            GL20.glDeleteShader(shader);
        }
    }
}
