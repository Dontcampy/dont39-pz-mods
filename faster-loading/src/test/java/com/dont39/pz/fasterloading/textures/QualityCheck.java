package com.dont39.pz.fasterloading.textures;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code qualityCheck} task: real tile pages, premultiplied as the game uploads them, compressed by the driver's
 * {@code GL_COMPRESSED_RGBA} and by {@link Dxt5Encoder} through {@link Upload}; PSNR of level 0 as the GPU decodes it.
 * Fails when ours is below the driver on colour or alpha. Needs a GPU, so it is not part of {@code check}.
 * Arguments: the game folder and the Workshop content folder.
 */
public final class QualityCheck {
    private static final int PAGES_PER_PACK = 6;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] IEND = {'I', 'E', 'N', 'D'};
    // a page both encoders keep exact (a fully opaque page's alpha) would make every mean infinite
    private static final double LOSSLESS = 100;

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
        Dxt5Encoder encoder = new Dxt5Encoder(Runtime.getRuntime().availableProcessors());
        Dxt5Encoder oneThread = new Dxt5Encoder(1);
        // the game encodes thousands of pages a join; time compiled code, not the first calls
        for (Page page : pages) {
            encoder.encode(page.rgba, page.width, page.height).close();
            oneThread.encode(page.rgba, page.width, page.height).close();
        }
        Totals driver = new Totals();
        Totals ours = new Totals();
        double oneThreadMs = 0;
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        System.out.println("PSNR dB rgb / alpha of level 0, driver then ours:");
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
            long nanos = System.nanoTime() - start;
            double[] byDriver = Dxt5Decoder.psnr(expected, readBack(width, height));
            driver.add(nanos, byDriver);
            if (driver.pages == 1) {
                System.out.printf("  (the driver picks internal format 0x%x for GL_COMPRESSED_RGBA)%n",
                        GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
            }

            start = System.nanoTime();
            Upload.compressed(encoder, GL11.GL_TEXTURE_2D, 0, width, height, page.rgba);
            GL11.glFinish();
            nanos = System.nanoTime() - start;
            double[] byUs = Dxt5Decoder.psnr(expected, readBack(width, height));
            ours.add(nanos, byUs);

            start = System.nanoTime();
            oneThread.encode(page.rgba, width, height).close();
            oneThreadMs += (System.nanoTime() - start) / 1e6;
            System.out.printf("  %-36s %4dx%-4d  %6.2f / %6.2f   %6.2f / %6.2f%n", page.name, width, height,
                    byDriver[0], byDriver[1], byUs[0], byUs[1]);
            STBImage.stbi_image_free(page.rgba);
        }
        GL11.glDeleteTextures(texture);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();

        int n = pages.size();
        System.out.printf("%d pages; mean PSNR dB rgb / alpha (a lossless page counts as %.0f dB), time per page on the"
                + " GL thread:%n", n, LOSSLESS);
        System.out.printf("  driver GL_COMPRESSED_RGBA  %.2f / %.2f   %.1f ms%n", driver.rgb / n, driver.alpha / n,
                driver.ms / n);
        System.out.printf("  %s on %d threads  %.2f / %.2f   %.1f ms (%.1f ms on one thread)%n", encoder.id(),
                encoder.threads(), ours.rgb / n, ours.alpha / n, ours.ms / n, oneThreadMs / n);
        if (ours.rgb < driver.rgb || ours.alpha < driver.alpha) {
            System.out.println("FAILED: below the driver");
            System.exit(1);
        }
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

    private static byte[] readBack(int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
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

    private record Page(String name, int width, int height, ByteBuffer rgba) {
    }

    private static final class Totals {
        int pages;
        double rgb;
        double alpha;
        double ms;

        void add(long nanos, double[] psnr) {
            pages++;
            ms += nanos / 1e6;
            rgb += Math.min(psnr[0], LOSSLESS);
            alpha += Math.min(psnr[1], LOSSLESS);
        }
    }
}
