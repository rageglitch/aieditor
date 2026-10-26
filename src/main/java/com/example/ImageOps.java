package com.example;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.Arrays;

public class ImageOps {

    private interface PixelOp {
        int apply(int argb);
    }

    private static BufferedImage blank(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    public static BufferedImage copy(BufferedImage src) {
        BufferedImage out = blank(src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    private static int pack(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage map(BufferedImage src, PixelOp op) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            px[i] = op.apply(px[i]);
        }
        BufferedImage out = blank(w, h);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    private static BufferedImage convolve(BufferedImage src, Kernel kernel) {
        BufferedImage out = blank(src.getWidth(), src.getHeight());
        new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null).filter(src, out);
        return out;
    }

    public static BufferedImage grayscale(BufferedImage src) {
        return map(src, p -> {
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int y = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            return pack(a, y, y, y);
        });
    }

    public static BufferedImage sepia(BufferedImage src) {
        return map(src, p -> {
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            double nr = 0.393 * r + 0.769 * g + 0.189 * b;
            double ng = 0.349 * r + 0.686 * g + 0.168 * b;
            double nb = 0.272 * r + 0.534 * g + 0.131 * b;
            return pack(a, clamp(nr), clamp(ng), clamp(nb));
        });
    }

    public static BufferedImage invert(BufferedImage src) {
        return map(src, p -> {
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            return pack(a, 255 - r, 255 - g, 255 - b);
        });
    }

    /** brightness, contrast, saturation each from -100 to 100. */
    public static BufferedImage adjust(BufferedImage src, double brightness, double contrast, double saturation) {
        final double cf = (100 + contrast) / 100.0;
        final double sf = (100 + saturation) / 100.0;
        final double shift = brightness * 2.55;
        return map(src, p -> {
            int a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            double gray = 0.299 * r + 0.587 * g + 0.114 * b;
            double nr = gray + (r - gray) * sf;
            double ng = gray + (g - gray) * sf;
            double nb = gray + (b - gray) * sf;
            nr = (nr - 128) * cf + 128 + shift;
            ng = (ng - 128) * cf + 128 + shift;
            nb = (nb - 128) * cf + 128 + shift;
            return pack(a, clamp(nr), clamp(ng), clamp(nb));
        });
    }

    public static BufferedImage blur(BufferedImage src, int radius) {
        int size = radius * 2 + 1;
        float[] k = new float[size * size];
        Arrays.fill(k, 1f / (size * size));
        return convolve(src, new Kernel(size, size, k));
    }

    public static BufferedImage sharpen(BufferedImage src) {
        float[] k = {
            0, -1, 0,
            -1, 5, -1,
            0, -1, 0
        };
        return convolve(src, new Kernel(3, 3, k));
    }

    public static BufferedImage pixelate(BufferedImage src, int block) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = blank(w, h);
        for (int y = 0; y < h; y += block) {
            for (int x = 0; x < w; x += block) {
                int p = src.getRGB(Math.min(x + block / 2, w - 1), Math.min(y + block / 2, h - 1));
                for (int yy = y; yy < Math.min(y + block, h); yy++) {
                    for (int xx = x; xx < Math.min(x + block, w); xx++) {
                        out.setRGB(xx, yy, p);
                    }
                }
            }
        }
        return out;
    }

    public static BufferedImage rotate(BufferedImage src, boolean clockwise) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = blank(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                if (clockwise) {
                    out.setRGB(h - 1 - y, x, p);
                } else {
                    out.setRGB(y, w - 1 - x, p);
                }
            }
        }
        return out;
    }

    public static BufferedImage flip(BufferedImage src, boolean horizontal) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = blank(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                if (horizontal) {
                    out.setRGB(w - 1 - x, y, p);
                } else {
                    out.setRGB(x, h - 1 - y, p);
                }
            }
        }
        return out;
    }

    public static BufferedImage crop(BufferedImage src, int x, int y, int w, int h) {
        x = Math.max(0, Math.min(x, src.getWidth() - 1));
        y = Math.max(0, Math.min(y, src.getHeight() - 1));
        w = Math.max(1, Math.min(w, src.getWidth() - x));
        h = Math.max(1, Math.min(h, src.getHeight() - y));
        return copy(src.getSubimage(x, y, w, h));
    }

    public static BufferedImage resize(BufferedImage src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        BufferedImage out = blank(w, h);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Scales down so the longest side is at most max pixels (fast previews). */
    public static BufferedImage fit(BufferedImage src, int max) {
        int big = Math.max(src.getWidth(), src.getHeight());
        if (big <= max) {
            return src;
        }
        double s = (double) max / big;
        return resize(src, (int) (src.getWidth() * s), (int) (src.getHeight() * s));
    }

    /** Puts a solid colour behind transparent areas. rgb like 0xFFFFFF. */
    public static BufferedImage fillBackground(BufferedImage src, int rgb) {
        BufferedImage out = blank(src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Opaque RGB copy, needed for saving JPEG. */
    public static BufferedImage flatten(BufferedImage src, int rgb) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}