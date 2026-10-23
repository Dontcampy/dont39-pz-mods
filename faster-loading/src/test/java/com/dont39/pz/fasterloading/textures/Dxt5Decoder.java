package com.dont39.pz.fasterloading.textures;

import java.nio.ByteBuffer;

/** A plain DXT5 decoder to check the encoder's output against its source. */
final class Dxt5Decoder {
    private Dxt5Decoder() {
    }

    /** RGBA bytes of a {@code width}×{@code height} level from its blocks. */
    static byte[] decode(ByteBuffer blocks, int width, int height) {
        int columns = Blocks.columns(width);
        byte[] out = new byte[width * height * 4];
        int[] palette = new int[4];
        for (int by = 0; by < Blocks.rows(height); by++) {
            for (int bx = 0; bx < columns; bx++) {
                int at = blocks.position() + (by * columns + bx) * 16;
                long alphaBlock = 0;
                for (int i = 0; i < 8; i++) {
                    alphaBlock |= (blocks.get(at + i) & 0xFFL) << (8 * i);
                }
                int[] alphas = alphaPalette((int) (alphaBlock & 0xFF), (int) ((alphaBlock >>> 8) & 0xFF));
                int c0 = (blocks.get(at + 8) & 0xFF) | (blocks.get(at + 9) & 0xFF) << 8;
                int c1 = (blocks.get(at + 10) & 0xFF) | (blocks.get(at + 11) & 0xFF) << 8;
                palette[0] = rgb(c0);
                palette[1] = rgb(c1);
                palette[2] = mix(palette[0], palette[1]);
                palette[3] = mix(palette[1], palette[0]);
                int indices = (blocks.get(at + 12) & 0xFF) | (blocks.get(at + 13) & 0xFF) << 8
                        | (blocks.get(at + 14) & 0xFF) << 16 | (blocks.get(at + 15) & 0xFF) << 24;
                for (int p = 0; p < 16; p++) {
                    int x = bx * 4 + p % 4;
                    int y = by * 4 + p / 4;
                    if (x >= width || y >= height) {
                        continue;
                    }
                    int c = palette[(indices >>> (2 * p)) & 3];
                    int o = (y * width + x) * 4;
                    out[o] = (byte) (c >>> 16);
                    out[o + 1] = (byte) (c >>> 8);
                    out[o + 2] = (byte) c;
                    out[o + 3] = (byte) alphas[(int) ((alphaBlock >>> (16 + 3 * p)) & 7)];
                }
            }
        }
        return out;
    }

    // written out apart from the encoder's, so that a mistake there does not cancel out here
    private static int[] alphaPalette(int a0, int a1) {
        if (a0 > a1) {
            return new int[] {a0, a1, (6 * a0 + a1 + 3) / 7, (5 * a0 + 2 * a1 + 3) / 7, (4 * a0 + 3 * a1 + 3) / 7,
                    (3 * a0 + 4 * a1 + 3) / 7, (2 * a0 + 5 * a1 + 3) / 7, (a0 + 6 * a1 + 3) / 7};
        }
        return new int[] {a0, a1, (4 * a0 + a1 + 2) / 5, (3 * a0 + 2 * a1 + 2) / 5, (2 * a0 + 3 * a1 + 2) / 5,
                (a0 + 4 * a1 + 2) / 5, 0, 255};
    }

    // RGB565 to 0xRRGGBB
    private static int rgb(int c) {
        int r = (c >>> 11) & 31;
        int g = (c >>> 5) & 63;
        int b = c & 31;
        return (r << 3 | r >>> 2) << 16 | (g << 2 | g >>> 4) << 8 | (b << 3 | b >>> 2);
    }

    // (2a + b) / 3 per channel
    private static int mix(int a, int b) {
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int v = (2 * ((a >>> shift) & 255) + ((b >>> shift) & 255) + 1) / 3;
            out |= v << shift;
        }
        return out;
    }

    /** {rgb, alpha} PSNR in dB of {@code decoded} against {@code source}, both RGBA. */
    static double[] psnr(byte[] source, byte[] decoded) {
        double rgb = 0;
        double alpha = 0;
        for (int i = 0; i < source.length; i += 4) {
            for (int c = 0; c < 3; c++) {
                int d = (source[i + c] & 0xFF) - (decoded[i + c] & 0xFF);
                rgb += d * d;
            }
            int d = (source[i + 3] & 0xFF) - (decoded[i + 3] & 0xFF);
            alpha += d * d;
        }
        double pixels = source.length / 4.0;
        return new double[] {db(rgb / (pixels * 3)), db(alpha / pixels)};
    }

    private static double db(double mse) {
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
    }
}
