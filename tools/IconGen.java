/**
 * Generates BoxHub's launcher icon, TV banner and notification icon as real PNGs.
 *
 * Why this exists instead of the vector drawables it replaces:
 *
 *   1. The old icon was a full-bleed #171614 square with a strokeWidth-4 outline
 *      on a 108 viewport. Rasterised into a 48 px launcher slot that is a
 *      near-black tile on a near-black TV launcher with a ~2 px hairline on it:
 *      the icon was effectively invisible, which is what got reported as
 *      "应用图标展示不出来".
 *   2. A launcher icon that exists only as a VectorDrawable in res/drawable has
 *      no fallback for launchers that will not inflate a vector cross-process,
 *      and no adaptive icon for API 26+. A PNG per density bucket works
 *      everywhere from API 24 up.
 *   3. The notification reused the opaque full-bleed launcher drawable. Android
 *      masks a notification icon to its alpha channel and tints it white, so the
 *      shade showed a solid white square. The notification needs its own
 *      alpha-only silhouette.
 *
 * Palette and mark are the app's own: cream tile, ink glyph, green accent, the
 * hexagon-plus-bar "box" from the original vector — drawn bold enough to survive
 * being 48 px wide.
 *
 * Regenerate after any palette change:
 *     java tools/IconGen.java app/src/main/res
 */
import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class IconGen {

    private static final Color INK = new Color(0x17, 0x16, 0x14);
    private static final Color CREAM = new Color(0xF6, 0xF6, 0xF4);
    private static final Color ACCENT = new Color(0x1F, 0x6F, 0x4A);

    /** Rendered large and downscaled: Java2D has no proper sub-pixel path AA. */
    private static final int SUPERSAMPLE = 4;

    private static final int[] DPI = { 48, 72, 96, 144, 192 };
    private static final String[] DPI_NAME = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };

    public static void main(String[] args) throws IOException {
        File res = new File(args.length > 0 ? args[0] : "app/src/main/res");

        for (int i = 0; i < DPI.length; i++) {
            // Launcher icon.
            write(res, "mipmap-" + DPI_NAME[i], "ic_launcher.png", launcher(DPI[i]));

            // Notification icon: 24 dp, so half the launcher size in px.
            write(res, "drawable-" + DPI_NAME[i], "ic_stat_pin.png", notification(DPI[i] / 2));
        }

        // The Android TV banner spec is exactly 320x180 px and must not be
        // rescaled by density, hence drawable-nodpi.
        write(res, "drawable-nodpi", "ic_banner.png", banner(320, 180));

        System.out.println("icons written under " + res.getPath());
    }

    /* ---------------------------------------------------------------- art */

    /** Cream rounded tile so the icon has an edge on a dark launcher. */
    private static BufferedImage launcher(int size) {
        BufferedImage img = blank(size, size);
        Graphics2D g = graphics(img, size);
        double s = size;
        g.setColor(CREAM);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.44, s * 0.44));
        glyph(g, s / 2, s / 2, s * 0.30, INK, ACCENT, s * 0.115);
        return downscale(g, img);
    }

    /**
     * Alpha-only silhouette: Android tints the notification icon and throws away
     * colour, so only the shape survives. It must be legible at 24 dp as a blob.
     */
    private static BufferedImage notification(int size) {
        BufferedImage img = blank(size, size);
        Graphics2D g = graphics(img, size);
        double s = size;
        glyph(g, s / 2, s / 2, s * 0.38, Color.WHITE, Color.WHITE, s * 0.16);
        return downscale(g, img);
    }

    /** 320x180 TV banner: mark plus wordmark, on the same cream tile. */
    private static BufferedImage banner(int w, int h) {
        BufferedImage img = blank(w, h);
        Graphics2D g = graphics(img, w, h);
        g.setColor(CREAM);
        g.fillRect(0, 0, w, h);

        final String word = "BoxHub";
        double r = h * 0.20;
        double glyphOuter = r * 1.21;          // ring stroke extends past r
        double gap = h * 0.15;
        int tracking = (int) (h * 0.028);

        // Fit the wordmark by measurement instead of guessing a font size: a
        // hardcoded size overflowed the 320 px canvas and clipped the word.
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int) (h * 0.30));
        int textW = 0;
        for (int size = (int) (h * 0.30); size >= 12; size -= 1) {
            font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            textW = measure(g, font, word, tracking);
            if (glyphOuter * 2 + gap + textW <= w * 0.92) break;
        }
        g.setFont(font);
        g.setColor(INK);

        double groupW = glyphOuter * 2 + gap + textW;
        double startX = (w - groupW) / 2;
        double cx = startX + glyphOuter;
        int baseline = (int) (h / 2 + font.getSize() * 0.35);

        glyph(g, cx, h / 2, r, INK, ACCENT, h * 0.070);
        // Set explicitly: glyph() leaves the accent colour on the context, and
        // the wordmark is meant to be ink.
        g.setColor(INK);
        tracked(g, word, (int) (startX + glyphOuter * 2 + gap), baseline, tracking);

        // A green rule ties the wordmark to the accent in the mark.
        double ruleH = Math.max(3, h * 0.032);
        g.setColor(ACCENT);
        g.fill(new RoundRectangle2D.Double(startX + glyphOuter * 2 + gap,
            baseline + h * 0.055, textW, ruleH, ruleH, ruleH));

        return downscale(g, img);
    }

    private static int measure(Graphics2D g, Font font, String text, int tracking) {
        FontMetrics fm = g.getFontMetrics(font);
        int w = 0;
        for (char ch : text.toCharArray()) w += fm.charWidth(ch);
        return w + tracking * (text.length() - 1);
    }

    /**
     * The mark: a hexagon ring (stroke) around a solid bar. Same idea as the
     * original vector, but the ring is a real stroke width rather than a
     * hairline, so it survives at launcher size.
     */
    private static void glyph(Graphics2D g, double cx, double cy, double r,
                              Color ring, Color core, double barWidth) {
        Path2D hex = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(90 + i * 60);
            double x = cx + r * Math.cos(a);
            double y = cy - r * Math.sin(a);
            if (i == 0) hex.moveTo(x, y); else hex.lineTo(x, y);
        }
        hex.closePath();

        g.setColor(ring);
        g.setStroke(new BasicStroke((float) (r * 0.42), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(hex);

        double bw = barWidth;
        double bh = r * 1.02;
        g.setColor(core);
        g.fill(new RoundRectangle2D.Double(cx - bw / 2, cy - bh / 2, bw, bh,
            bw * 0.28, bw * 0.28));
    }

    /* ------------------------------------------------------------ plumbing */

    private static void tracked(Graphics2D g, String text, int x, int y, int tracking) {
        Font font = g.getFont();
        FontMetrics fm = g.getFontMetrics(font);
        int cx = x;
        for (char ch : text.toCharArray()) {
            g.drawString(String.valueOf(ch), cx, y);
            cx += fm.charWidth(ch) + tracking;
        }
    }

    private static BufferedImage blank(int w, int h) {
        // TYPE_INT_ARGB: the launcher icon needs real alpha at the corners.
        return new BufferedImage(w * SUPERSAMPLE, h * SUPERSAMPLE, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D graphics(BufferedImage img, int... logical) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        // Drawing code works in final-pixel units; scale up underneath it.
        AffineTransform at = AffineTransform.getScaleInstance(SUPERSAMPLE, SUPERSAMPLE);
        g.setTransform(at);
        return g;
    }

    private static BufferedImage downscale(Graphics2D g, BufferedImage big) {
        g.dispose();
        int w = big.getWidth() / SUPERSAMPLE;
        int h = big.getHeight() / SUPERSAMPLE;
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = small.createGraphics();
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        o.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        o.drawImage(big, 0, 0, w, h, null);
        o.dispose();
        return small;
    }

    private static void write(File res, String dir, String name, BufferedImage img) throws IOException {
        File d = new File(res, dir);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not create " + d);
        File f = new File(d, name);
        ImageIO.write(img, "png", f);
        System.out.printf("  %-34s %dx%d  %d bytes%n", dir + "/" + name, img.getWidth(), img.getHeight(), f.length());
    }
}