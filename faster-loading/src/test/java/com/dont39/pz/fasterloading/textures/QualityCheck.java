package com.dont39.pz.fasterloading.textures;

import com.dont39.pz.fasterloading.textures.bc7.Bc7Encoder;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code qualityCheck} task: real tile pages, premultiplied as the game uploads them, compressed by the driver's
 * {@code GL_COMPRESSED_RGBA}, by {@link Dxt5Encoder} and by {@link Bc7Encoder} through {@link Upload}; PSNR of what the
 * GPU decodes. Fails when DXT5 is below the driver on colour or alpha; when BC7 is below DXT5 on colour, over white or
 * over grey; when a BC7 level of a mip chain or a large level is not BPTC of its size or decodes below a floor; when a
 * BC7 level changes the GL state around it; or when BC7 takes more of the GL thread a page with all its levels than
 * {@link #MAX_GL_THREAD_SHARE} of DXT5's in the same run. Needs a GPU, so it is not part of {@code check}. Arguments: the game folder and the Workshop content folder.
 */
public final class QualityCheck {
    private static final int PAGES_PER_PACK = 6;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] IEND = {'I', 'E', 'N', 'D'};
    // a page both encoders keep exact (a fully opaque page's alpha) would make every mean infinite
    private static final double LOSSLESS = 100;
    // blocks decoded wrong are far below it; a small level of a detailed page is near 25 dB with DXT5 too
    private static final double LEVEL_FLOOR = 20;
    // a strip of real page content; one encoded from the wrong rows or put at the wrong place lands far below
    private static final double STRIP_FLOOR = 30;
    // relative to DXT5 in the same run: the absolute time follows how busy the machine is (1.33-1.55 ms, both alike)
    private static final double MAX_GL_THREAD_SHARE = 0.4;
    private static final int RUNS = 7;
    private static final int BPTC = GL42.GL_COMPRESSED_RGBA_BPTC_UNORM;

    private static final List<String> failures = new ArrayList<>();

    private QualityCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path game = Path.of(args[0]);
        Path workshop = Path.of(args[1]);
        Path melos = workshop.resolve("2879745353/mods/Melos Tiles for Miles/common/media/texturepacks");
        Path drazion = workshop.resolve("3046728955/mods/Drazions Tile Pack/common/media/texturepacks");
        List<Path> packs = new ArrayList<>();
        for (Path pack : List.of(melos.resolve("melos_tiles_for_miles1.pack"),
                melos.resolve("melos_tiles_for_miles2.pack"), game.resolve("media/texturepacks/Tiles2x.pack"),
                drazion.resolve("tikitown_tiles.floor.pack"))) {
            if (Files.isRegularFile(pack)) {
                packs.add(pack);
            } else {
                System.out.println("skipped, not installed: " + pack);
            }
        }
        if (packs.isEmpty()) {
            throw new IllegalStateException("none of the sample packs is installed");
        }
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW did not initialise");
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(64, 64, "qualityCheck", 0, 0);
        if (window == 0) {
            throw new IllegalStateException("no OpenGL window");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
        if (!GL.getCapabilities().OpenGL43) {
            throw new IllegalStateException("the GL has no OpenGL 4.3, which the BC7 encoder needs");
        }
        List<Page> pages = new ArrayList<>();
        for (Path pack : packs) {
            byte[] bytes = Files.readAllBytes(pack);
            List<int[]> pngs = pngs(bytes);
            // spread over the pack; a pack with fewer pages gives each once
            int count = Math.min(PAGES_PER_PACK, pngs.size());
            for (int k = 0; k < count; k++) {
                int[] range = pngs.get((int) ((long) k * pngs.size() / count));
                pages.add(page(pack.getFileName() + " #" + k, bytes, range[0], range[1]));
            }
        }
        Dxt5Encoder dxt5 = new Dxt5Encoder(Runtime.getRuntime().availableProcessors());
        long start = System.nanoTime();
        Bc7Encoder bc7 = new Bc7Encoder();
        System.out.printf("%s: programs built in %.0f ms%n", bc7.id(), (System.nanoTime() - start) / 1e6);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

        compare(pages, dxt5, bc7);
        mipChains(pages.get(0), dxt5, bc7);
        large(pages, bc7);
        glState(pages.get(0), bc7);
        timing(pages, dxt5, bc7);

        for (Page page : pages) {
            STBImage.stbi_image_free(page.rgba);
        }
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    // level 0 of each page by the driver, DXT5 and BC7
    private static void compare(List<Page> pages, Dxt5Encoder dxt5, Bc7Encoder bc7) {
        Dxt5Encoder oneThread = new Dxt5Encoder(1);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // the game encodes thousands of pages a join; time compiled code and built programs, not the first calls
        for (Page page : pages) {
            dxt5.encode(page.rgba, page.width, page.height).close();
            oneThread.encode(page.rgba, page.width, page.height).close();
        }
        for (Page page : pages.subList(0, Math.min(3, pages.size()))) {
            Upload.compressed(bc7, GL11.GL_TEXTURE_2D, 0, page.width, page.height, page.rgba);
        }
        GL11.glFinish();
        Totals driver = new Totals();
        Totals ours = new Totals();
        Totals gpu = new Totals();
        double oneThreadMs = 0;
        System.out.println("level 0, PSNR dB rgb / alpha and over white / over grey; driver, then DXT5, then BC7:");
        for (Page page : pages) {
            int width = page.width;
            int height = page.height;
            byte[] expected = new byte[width * height * 4];
            page.rgba.get(0, expected);

            GL11.glFinish();
            long start = System.nanoTime();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL13.GL_COMPRESSED_RGBA, width, height, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, page.rgba);
            GL11.glFinish();
            driver.add(System.nanoTime() - start, expected, readBack(width, height, 0));
            if (driver.pages() == 1) {
                System.out.printf("  (the driver picks internal format 0x%x for GL_COMPRESSED_RGBA)%n",
                        GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
            }

            start = System.nanoTime();
            Upload.compressed(dxt5, GL11.GL_TEXTURE_2D, 0, width, height, page.rgba);
            GL11.glFinish();
            ours.add(System.nanoTime() - start, expected, readBack(width, height, 0));

            start = System.nanoTime();
            Upload.compressed(bc7, GL11.GL_TEXTURE_2D, 0, width, height, page.rgba);
            GL11.glFinish();
            gpu.add(System.nanoTime() - start, expected, readBack(width, height, 0));

            start = System.nanoTime();
            oneThread.encode(page.rgba, width, height).close();
            oneThreadMs += (System.nanoTime() - start) / 1e6;
            System.out.printf("  %-30s %4dx%-4d  %s   %s   %s%n", page.name, width, height, driver.last(), ours.last(),
                    gpu.last());
        }
        GL11.glDeleteTextures(texture);

        int n = pages.size();
        System.out.printf("%d pages; means in dB (a lossless page counts as %.0f dB), level 0 until the GPU is done:%n",
                n, LOSSLESS);
        System.out.printf("  %-60s %s%n", "driver GL_COMPRESSED_RGBA", driver.means());
        System.out.printf("  %-60s %s (%.1f ms on one thread)%n", dxt5.id() + " on " + dxt5.threads() + " threads",
                ours.means(), oneThreadMs / n);
        System.out.printf("  %-60s %s%n", bc7.id(), gpu.means());
        System.out.println("alpha pixels off by more than 8 (sum over the pages), the largest alpha error; the same over"
                + " white:");
        System.out.printf("  %-60s %s%n", "driver", driver.worst());
        System.out.printf("  %-60s %s%n", "DXT5", ours.worst());
        System.out.printf("  %-60s %s%n", "BC7", gpu.worst());
        System.out.printf("pages where BC7 is below DXT5: over white %d, over grey %d, rgb %d, alpha %d%n",
                gpu.below(ours, 2), gpu.below(ours, 3), gpu.below(ours, 0), gpu.below(ours, 1));
        if (ours.mean(0) < driver.mean(0) || ours.mean(1) < driver.mean(1)) {
            failures.add("DXT5 below the driver on colour or alpha");
        }
        if (gpu.mean(0) <= ours.mean(0) || gpu.mean(2) <= ours.mean(2) || gpu.mean(3) <= ours.mean(3)) {
            failures.add("BC7 not above DXT5 on colour, over white or over grey");
        }
    }

    // full chains of odd sizes down to 1x1: every level BPTC of its size (the texture complete), each above the floor
    private static void mipChains(Page page, Dxt5Encoder dxt5, Bc7Encoder bc7) {
        System.out.println("BC7 mip chains (from " + page.name + "), every level down to 1x1:");
        int[][] sizes = {{1023, 1019}, {37, 5}, {5, 37}, {13, 2}, {3, 1}, {1, 1}};
        for (int[] size : sizes) {
            List<ByteBuffer> chain = chain(crop(page, size[0], size[1]), size[0], size[1]);
            int texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            int width = size[0];
            int height = size[1];
            for (int level = 0; level < chain.size(); level++) {
                Upload.compressed(bc7, GL11.GL_TEXTURE_2D, level, width, height, chain.get(level));
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
            width = size[0];
            height = size[1];
            double lowest = Double.POSITIVE_INFINITY;
            String where = "every level lossless";
            boolean complete = true;
            for (int level = 0; level < chain.size(); level++) {
                int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                if (format != BPTC || w != width || h != height) {
                    complete = false;
                    failures.add(String.format("%dx%d chain, level %d: format 0x%x %dx%d, not BPTC %dx%d", size[0],
                            size[1], level, format, w, h, width, height));
                }
                byte[] expected = new byte[width * height * 4];
                chain.get(level).get(0, expected);
                double[] psnr = Dxt5Decoder.psnr(expected, readBack(width, height, level));
                double[] byDxt5;
                try (Blocks blocks = dxt5.encode(chain.get(level), width, height)) {
                    byDxt5 = Dxt5Decoder.psnr(expected, Dxt5Decoder.decode(blocks.data(), width, height));
                }
                for (int c = 0; c < 2; c++) {
                    if (psnr[c] < lowest) {
                        lowest = psnr[c];
                        where = String.format("%s of level %d (%dx%d; DXT5 %.2f)", c == 0 ? "rgb" : "alpha", level,
                                width, height, byDxt5[c]);
                    }
                }
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
            System.out.printf("  %4dx%-4d %2d levels: %s; lowest PSNR %.2f dB, %s%n", size[0], size[1], chain.size(),
                    complete ? "all BPTC of their sizes, complete" : "NOT COMPLETE", lowest, where);
            if (lowest < LEVEL_FLOOR) {
                failures.add(String.format("%dx%d chain: %s at %.2f dB, below %.0f", size[0], size[1], where, lowest,
                        LEVEL_FLOOR));
            }
            GL11.glDeleteTextures(texture);
            chain.forEach(MemoryUtil::memFree);
        }
    }

    // levels larger than the encoder's buffers, so encoded in strips
    private static void large(List<Page> pages, Bc7Encoder bc7) {
        // four full strips, each more work groups than one row of the dispatch grid holds
        large(pages, bc7, 2048, 2048);
        // a full strip and a shorter last one, both with a partial block column, the last with a partial block row
        large(pages, bc7, 1501, 1099);
    }

    private static void large(List<Page> pages, Bc7Encoder bc7, int width, int height) {
        ByteBuffer rgba = MemoryUtil.memAlloc(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Page page = pages.get(Math.min(pages.size() - 1, (y / 1024) * 2 + x / 1024));
                int px = Math.min(x % 1024, page.width - 1);
                int py = Math.min(y % 1024, page.height - 1);
                rgba.putInt((y * width + x) * 4, page.rgba.getInt((py * page.width + px) * 4));
            }
        }
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // as the game's textures without mipmaps: level 0 alone, no chain allocated
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        Upload.compressed(bc7, GL11.GL_TEXTURE_2D, 0, width, height, rgba);
        int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        int level1 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 1, GL11.GL_TEXTURE_WIDTH);
        byte[] expected = new byte[width * height * 4];
        rgba.get(0, expected);
        byte[] decoded = readBack(width, height, 0);
        double[] psnr = Dxt5Decoder.psnr(expected, decoded);
        // a strip encoded wrong or placed at the wrong rows shows in its own rows' PSNR
        int rows = Bc7Encoder.stripRows(width, height);
        double lowest = Double.POSITIVE_INFINITY;
        for (int y = 0; y < height; y += rows) {
            int from = y * width * 4;
            int to = Math.min(height, y + rows) * width * 4;
            double[] strip = Dxt5Decoder.psnr(Arrays.copyOfRange(expected, from, to), Arrays.copyOfRange(decoded, from,
                    to));
            lowest = Math.min(lowest, Math.min(strip[0], strip[1]));
        }
        System.out.printf("BC7 %dx%d level (real pages, no mipmaps, strips of %d rows): format 0x%x, PSNR %.2f / %.2f dB,"
                + " lowest strip %.2f dB, level 1 %s%n", width, height, rows, format, psnr[0], psnr[1], lowest,
                level1 == 0 ? "not allocated" : level1 + " wide");
        if (format != BPTC || Math.min(psnr[0], psnr[1]) < LEVEL_FLOOR || lowest < STRIP_FLOOR || level1 != 0) {
            failures.add(String.format("the %dx%d level: format 0x%x, PSNR %.2f / %.2f dB, lowest strip %.2f dB, level 1"
                    + " %d wide", width, height, format, psnr[0], psnr[1], lowest, level1));
        }
        GL11.glDeleteTextures(texture);
        MemoryUtil.memFree(rgba);
    }

    // the encoder's binding points range-bound, base-bound and unbound, a program and the generic binding set
    private static void glState(Page page, Bc7Encoder bc7) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, "#version 430\nlayout(local_size_x = 1) in;\nvoid main() {\n}\n");
        GL20.glCompileShader(shader);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("test program: " + GL20.glGetProgramInfoLog(program));
        }
        int[] buffers = new int[3];
        GL15.glGenBuffers(buffers);
        for (int buffer : buffers) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4096, GL15.GL_STATIC_DRAW);
        }
        int alignment = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int texture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUseProgram(program);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffers[0], alignment, 256);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, buffers[1]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffers[2]);
        String before = state();
        ByteBuffer rgba = crop(page, 64, 64);
        Upload.compressed(bc7, GL11.GL_TEXTURE_2D, 0, 64, 64, rgba);
        String after = state();
        System.out.println("GL state around a BC7 level: " + (before.equals(after) ? "the same, " + after
                : "before " + before + ", after " + after));
        if (!before.equals(after)) {
            failures.add("a BC7 level changed the GL state: before " + before + ", after " + after);
        }
        GL20.glUseProgram(0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL11.glDeleteTextures(texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL15.glDeleteBuffers(buffers);
        GL20.glDeleteProgram(program);
        GL20.glDeleteShader(shader);
        MemoryUtil.memFree(rgba);
    }

    private static String state() {
        StringBuilder s = new StringBuilder("program " + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) + ", generic SSBO "
                + GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        for (int i = 0; i < 3; i++) {
            s.append(", SSBO ").append(i).append(' ')
                    .append(GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, i)).append(" @")
                    .append(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, i)).append('+')
                    .append(GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, i));
        }
        return s.append(", unpack buffer ").append(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING))
                .append(", texture unit ").append(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0)
                .append(" texture ").append(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)).toString();
    }

    // every page with all its levels into a new texture, as the game uploads them; runs interleaved, medians
    private static void timing(List<Page> pages, Dxt5Encoder dxt5, Bc7Encoder bc7) {
        int n = pages.size();
        List<List<ByteBuffer>> chains = new ArrayList<>();
        for (Page page : pages) {
            chains.add(chain(page.rgba, page.width, page.height));
        }
        Encoder[] encoders = {dxt5, bc7};
        double[][] glThread = new double[encoders.length][RUNS];
        double[][] done = new double[encoders.length][RUNS];
        double[] gpu = new double[RUNS];
        for (int run = 0; run < RUNS; run++) {
            for (int k = 0; k < encoders.length; k++) {
                boolean timed = encoders[k] == bc7;
                int[] textures = new int[n];
                GL11.glGenTextures(textures);
                int[] queries = new int[n];
                if (timed) {
                    GL15.glGenQueries(queries);
                }
                GL11.glFinish();
                long start = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                    if (timed) {
                        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[i]);
                    }
                    int width = pages.get(i).width;
                    int height = pages.get(i).height;
                    for (int level = 0; level < chains.get(i).size(); level++) {
                        // as the hook does per level
                        if (!Upload.packedRows(width)) {
                            throw new IllegalStateException("unpack state not packed");
                        }
                        Upload.compressed(encoders[k], GL11.GL_TEXTURE_2D, level, width, height,
                                chains.get(i).get(level));
                        width = Math.max(1, width / 2);
                        height = Math.max(1, height / 2);
                    }
                    if (timed) {
                        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                    }
                }
                glThread[k][run] = (System.nanoTime() - start) / 1e6 / n;
                GL11.glFinish();
                done[k][run] = (System.nanoTime() - start) / 1e6 / n;
                if (timed) {
                    long nanos = 0;
                    for (int query : queries) {
                        nanos += GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT);
                    }
                    gpu[run] = nanos / 1e6 / n;
                    GL15.glDeleteQueries(queries);
                }
                GL11.glDeleteTextures(textures);
            }
        }
        System.out.printf("%d pages with all their levels, a new texture each; medians of %d interleaved runs, per"
                + " page:%n", n, RUNS);
        for (int k = 0; k < encoders.length; k++) {
            System.out.printf("  %-60s GL thread %5.2f ms, until the GPU is done %5.2f ms%s%n", encoders[k].id(),
                    median(glThread[k]), median(done[k]),
                    encoders[k] == bc7 ? String.format(", GPU time %5.2f ms", median(gpu)) : "");
        }
        double share = median(glThread[1]) / median(glThread[0]);
        System.out.printf("  BC7's GL thread a page is %.0f%% of DXT5's (limit %.0f%%)%n", share * 100,
                MAX_GL_THREAD_SHARE * 100);
        if (share > MAX_GL_THREAD_SHARE) {
            failures.add(String.format("BC7 takes %.2f ms of the GL thread a page, %.0f%% of DXT5's %.2f ms, more than"
                    + " %.0f%%", median(glThread[1]), share * 100, median(glThread[0]), MAX_GL_THREAD_SHARE * 100));
        }
        for (List<ByteBuffer> chain : chains) {
            // level 0 is the page's
            chain.subList(1, chain.size()).forEach(MemoryUtil::memFree);
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    // level 0 and its halves down to 1x1, each pixel the mean of the 2x2 above it (a last odd row or column dropped)
    private static List<ByteBuffer> chain(ByteBuffer level0, int width, int height) {
        List<ByteBuffer> chain = new ArrayList<>();
        chain.add(level0);
        ByteBuffer above = level0;
        while (width > 1 || height > 1) {
            int w = Math.max(1, width / 2);
            int h = Math.max(1, height / 2);
            ByteBuffer level = MemoryUtil.memAlloc(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int c = 0; c < 4; c++) {
                        int sum = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int sx = Math.min(2 * x + dx, width - 1);
                                int sy = Math.min(2 * y + dy, height - 1);
                                sum += above.get((sy * width + sx) * 4 + c) & 0xFF;
                            }
                        }
                        level.put((y * w + x) * 4 + c, (byte) ((sum + 2) / 4));
                    }
                }
            }
            chain.add(level);
            above = level;
            width = w;
            height = h;
        }
        return chain;
    }

    // a width x height cut from the page's middle, edge pixels repeated where the page is smaller
    private static ByteBuffer crop(Page page, int width, int height) {
        ByteBuffer out = MemoryUtil.memAlloc(width * height * 4);
        int x0 = Math.max(0, (page.width - width) / 2);
        int y0 = Math.max(0, (page.height - height) / 2);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = Math.min(x0 + x, page.width - 1);
                int py = Math.min(y0 + y, page.height - 1);
                out.putInt((y * width + x) * 4, page.rgba.getInt((py * page.width + px) * 4));
            }
        }
        return out;
    }

    private static Page page(String name, byte[] pack, int from, int to) {
        ByteBuffer png = MemoryUtil.memAlloc(to - from).put(pack, from, to - from).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer rgba = STBImage.stbi_load_from_memory(png, w, h, channels, 4);
            if (rgba == null) {
                throw new IllegalStateException(name + ": " + STBImage.stbi_failure_reason());
            }
            premultiply(rgba);
            return new Page(name, w.get(0), h.get(0), rgba);
        } finally {
            MemoryUtil.memFree(png);
        }
    }

    // the game's ImageData.performPreMultipliedAlpha, truncating
    private static void premultiply(ByteBuffer rgba) {
        for (int i = 0; i < rgba.limit(); i += 4) {
            int alpha = rgba.get(i + 3) & 0xFF;
            for (int c = 0; c < 3; c++) {
                rgba.put(i + c, (byte) (int) ((rgba.get(i + c) & 0xFF) * alpha / 255f));
            }
        }
    }

    private static byte[] readBack(int width, int height, int level) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[width * height * 4];
            pixels.get(0, out);
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    // a pack's pages are PNG files one after another, between the pack's own records
    private static List<int[]> pngs(byte[] pack) {
        List<int[]> pngs = new ArrayList<>();
        for (int start = indexOf(pack, PNG, 0); start >= 0; start = indexOf(pack, PNG, start + 1)) {
            int end = indexOf(pack, IEND, start);
            if (end < 0) {
                break;
            }
            // the chunk's CRC follows its type
            pngs.add(new int[] {start, end + 8});
            start = end;
        }
        return pngs;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // premultiplied source over a flat background, what the screen shows: colour and alpha errors together
    private static double composite(byte[] source, byte[] decoded, int background) {
        double sum = 0;
        for (int i = 0; i < source.length; i += 4) {
            int sa = source[i + 3] & 0xFF;
            int da = decoded[i + 3] & 0xFF;
            for (int c = 0; c < 3; c++) {
                double s = (source[i + c] & 0xFF) + (255 - sa) * background / 255.0;
                double d = (decoded[i + c] & 0xFF) + (255 - da) * background / 255.0;
                sum += (s - d) * (s - d);
            }
        }
        double mse = sum / (source.length / 4.0 * 3);
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    private record Page(String name, int width, int height, ByteBuffer rgba) {
    }

    // per page: rgb, alpha, over white, over grey (dB, capped at LOSSLESS); and the large errors
    private static final class Totals {
        final List<double[]> pages = new ArrayList<>();
        double ms;
        long alphaOff;
        int alphaMax;
        long whiteOff;
        int whiteMax;

        void add(long nanos, byte[] expected, byte[] decoded) {
            ms += nanos / 1e6;
            double[] psnr = Dxt5Decoder.psnr(expected, decoded);
            pages.add(new double[] {Math.min(psnr[0], LOSSLESS), Math.min(psnr[1], LOSSLESS),
                    Math.min(composite(expected, decoded, 255), LOSSLESS),
                    Math.min(composite(expected, decoded, 128), LOSSLESS)});
            for (int i = 0; i < expected.length; i += 4) {
                int sa = expected[i + 3] & 0xFF;
                int da = decoded[i + 3] & 0xFF;
                int alpha = Math.abs(sa - da);
                if (alpha > 8) {
                    alphaOff++;
                }
                alphaMax = Math.max(alphaMax, alpha);
                int white = 0;
                for (int c = 0; c < 3; c++) {
                    white = Math.max(white, Math.abs((expected[i + c] & 0xFF) + 255 - sa - (decoded[i + c] & 0xFF)
                            - (255 - da)));
                }
                if (white > 8) {
                    whiteOff++;
                }
                whiteMax = Math.max(whiteMax, white);
            }
        }

        int pages() {
            return pages.size();
        }

        double mean(int k) {
            return pages.stream().mapToDouble(p -> p[k]).average().orElseThrow();
        }

        String last() {
            double[] p = pages.get(pages.size() - 1);
            return String.format("%6.2f / %6.2f  %6.2f / %6.2f", p[0], p[1], p[2], p[3]);
        }

        String means() {
            return String.format("rgb / alpha %6.2f / %6.2f, over white / grey %6.2f / %6.2f, %5.1f ms", mean(0),
                    mean(1), mean(2), mean(3), ms / pages.size());
        }

        String worst() {
            return String.format("alpha %7d px, max %3d; over white %7d px, max %3d", alphaOff, alphaMax, whiteOff,
                    whiteMax);
        }

        int below(Totals other, int k) {
            int count = 0;
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i)[k] < other.pages.get(i)[k]) {
                    count++;
                }
            }
            return count;
        }
    }
}
