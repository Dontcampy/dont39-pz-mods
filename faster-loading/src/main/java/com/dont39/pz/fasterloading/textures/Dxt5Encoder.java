package com.dont39.pz.fasterloading.textures;

import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.GL13;
import org.lwjgl.stb.STBDXT;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DXT5, the format NVIDIA's driver picks for the game's {@code GL_COMPRESSED_RGBA}: colour by stb_dxt (in the game's
 * LWJGL), alpha by {@link Dxt5Alpha}. Blocks are independent, so a level's block rows are split over a pool; every
 * block is encoded the same way on any thread.
 */
public final class Dxt5Encoder implements Encoder {
    // below this the pool's hand-off costs about what it saves
    static final int PARALLEL_BLOCKS = 256;

    private final int threads;
    private volatile ExecutorService pool;

    public Dxt5Encoder(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads " + threads);
        }
        this.threads = threads;
    }

    public int threads() {
        return threads;
    }

    @Override
    public int glInternalFormat() {
        return EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
    }

    @Override
    public String id() {
        return "dxt5 (stb_dxt high quality colour, own alpha) 1";
    }

    @Override
    public void define(int target, int level, int width, int height, ByteBuffer rgba) {
        try (Blocks blocks = encode(rgba, width, height)) {
            GL13.glCompressedTexImage2D(target, level, glInternalFormat(), width, height, 0, blocks.data());
        }
    }

    /** The blocks of {@code width}×{@code height} tightly packed RGBA bytes starting at {@code rgba}'s position. */
    public Blocks encode(ByteBuffer rgba, int width, int height) {
        if (width < 1 || height < 1 || rgba.remaining() < (long) width * height * 4) {
            throw new IllegalArgumentException(width + "x" + height + " RGBA needs " + (long) width * height * 4
                    + " bytes, the buffer has " + rgba.remaining());
        }
        int columns = (width + 3) / 4;
        int rows = (height + 3) / 4;
        ByteBuffer out = MemoryUtil.memAlloc(columns * rows * 16);
        long src = MemoryUtil.memAddress(rgba);
        long dst = MemoryUtil.memAddress(out);
        int parts = columns * rows < PARALLEL_BLOCKS ? 1 : Math.min(threads, rows);
        try {
            if (parts == 1) {
                rows(src, width, height, dst, 0, rows);
            } else {
                parallel(src, width, height, dst, rows, parts);
            }
        } catch (RuntimeException | Error e) {
            MemoryUtil.memFree(out);
            throw e;
        }
        return new Blocks(out);
    }

    private void parallel(long src, int width, int height, long dst, int rows, int parts) {
        List<Future<?>> started = new ArrayList<>(parts);
        Throwable failure = null;
        try {
            ExecutorService executor = pool();
            for (int p = 0; p < parts; p++) {
                int first = (int) ((long) rows * p / parts);
                int end = (int) ((long) rows * (p + 1) / parts);
                started.add(executor.submit(() -> rows(src, width, height, dst, first, end)));
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        // the parts write the output and read the game's pixels, both freed once this returns or throws
        boolean interrupted = false;
        for (Future<?> f : started) {
            while (true) {
                try {
                    f.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    if (failure == null) {
                        failure = e.getCause();
                    }
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw new IllegalStateException("encoding a " + width + "x" + height + " level failed", failure);
        }
    }

    // block rows [first, end); a partial block repeats its last column and row, as encoders commonly pad
    private static void rows(long src, int width, int height, long dst, int first, int end) {
        int columns = (width + 3) / 4;
        int[] alpha = new int[16];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long block = stack.nmalloc(64);
            for (int by = first; by < end; by++) {
                for (int bx = 0; bx < columns; bx++) {
                    for (int y = 0; y < 4; y++) {
                        int py = Math.min(by * 4 + y, height - 1);
                        for (int x = 0; x < 4; x++) {
                            int px = Math.min(bx * 4 + x, width - 1);
                            int pixel = MemoryUtil.memGetInt(src + ((long) py * width + px) * 4);
                            MemoryUtil.memPutInt(block + (y * 4 + x) * 4, pixel);
                            alpha[y * 4 + x] = MemoryUtil.memGetByte(block + (y * 4 + x) * 4 + 3) & 0xFF;
                        }
                    }
                    long at = dst + ((long) by * columns + bx) * 16;
                    long a = Dxt5Alpha.encode(alpha);
                    for (int i = 0; i < 8; i++) {
                        MemoryUtil.memPutByte(at + i, (byte) (a >>> (8 * i)));
                    }
                    // the colour half of a DXT5 block is a 4-colour DXT1 block, which stb writes without alpha
                    STBDXT.nstb_compress_dxt_block(at + 8, block, 0, STBDXT.STB_DXT_HIGHQUAL);
                }
            }
        }
    }

    private ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (this) {
                p = pool;
                if (p == null) {
                    AtomicInteger n = new AtomicInteger();
                    p = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "Dont39FasterLoading-texture-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    pool = p;
                }
            }
        }
        return p;
    }
}
