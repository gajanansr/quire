import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders Folio's Play Store graphics: the 512x512 listing icon and the 1024x500
 * feature graphic.
 *
 * Generated rather than drawn, for one reason: the mark, the ground colour and the
 * page colour are already defined in the app, in
 * {@code res/drawable/ic_launcher_foreground.xml} and
 * {@code res/values/ic_launcher_colors.xml}. A store icon exported by hand from a
 * design tool is a fourth copy of that geometry, and it is the copy nobody looks at
 * again — so it is the one that is still the old blue two years later, on the single
 * image most people see before they ever install the app.
 *
 * Java2D specifically, and no imaging library: the build already requires JDK 21, so
 * this runs on any machine that can build Folio, with nothing else installed. Run it
 * through {@code scripts/generate-play-graphics.sh}.
 *
 * The curves below are transcribed from the vector drawable by hand. An SVG path
 * {@code C x1 y1, x2 y2, x y} is exactly {@code curveTo(x1, y1, x2, y2, x, y)}, so the
 * transcription is mechanical, and the numbers are left in the drawable's own 108dp
 * viewport coordinates so the two files can be read side by side.
 */
public final class PlayGraphics {

    // ---------------------------------------------------------------------
    // The brand, and only here
    // ---------------------------------------------------------------------

    /** {@code ic_launcher_background}, from oklch(0.42 0.09 250). */
    private static final Color GROUND = new Color(0x21, 0x4F, 0x7C);

    /** {@code ic_launcher_ink}, the page colour, from oklch(0.985 0.006 70). */
    private static final Color PAGE = new Color(0xFD, 0xF9, 0xF6);

    /** The far leaf sits in the gutter shadow. Same alpha as the drawable. */
    private static final float SHADOW_ALPHA = 0.8f;

    /** The mark's own bounds inside that viewport, from the path data. */
    private static final double MARK_LEFT = 29.2;
    private static final double MARK_RIGHT = 78.8;
    private static final double MARK_TOP = 34.4;
    private static final double MARK_BOTTOM = 71.9;
    private static final double MARK_WIDTH = MARK_RIGHT - MARK_LEFT;

    /**
     * How much of the store icon's width the mark spans.
     *
     * Deliberately larger than the adaptive icon's proportion, which is
     * 49.6/108 = 46%. That number exists because a launcher masks the 108dp canvas
     * down to a circle and the mark has to survive the crop — the 66dp safe circle
     * that {@code LauncherMarkTest} enforces. The Play listing icon is never masked:
     * it is a flat square, so reusing 46% would leave the mark marooned in a field of
     * blue next to every other icon on the page.
     *
     * 58% is the mark occupying roughly the same share of the *visible* area a
     * launcher shows (49.6 of a 72dp circle is 69%), pulled back enough that the
     * rounded corners Play draws over the square never come near it.
     */
    private static final double ICON_MARK_WIDTH_FRACTION = 0.58;

    private static final int ICON_SIZE = 512;
    private static final int FEATURE_WIDTH = 1024;
    private static final int FEATURE_HEIGHT = 500;

    /** Where the app's own typefaces live, relative to the repo root. */
    private static final String FONT_DIR = "app/src/main/res/font/";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: PlayGraphics <output-directory>");
            System.exit(2);
        }
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("cannot create " + out);
        }

        write(icon(), new File(out, "icon.png"));
        write(featureGraphic(), new File(out, "featureGraphic.png"));
    }

    private static void write(BufferedImage image, File file) throws Exception {
        ImageIO.write(image, "png", file);
        System.out.printf("  %-20s %dx%d  %,d bytes%n",
                file.getName(), image.getWidth(), image.getHeight(), file.length());
    }

    // ---------------------------------------------------------------------
    // The mark
    // ---------------------------------------------------------------------

    /** The leaf on the far side of the fold, in shadow. */
    private static Path2D.Double shadowLeaf() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(51, 39.6);
        p.curveTo(44.6, 35.9, 37.4, 34.4, 31.4, 35.4);
        p.curveTo(30.1, 35.6, 29.2, 36.7, 29.2, 38);
        p.lineTo(29.2, 66);
        p.curveTo(29.2, 67.5, 30.5, 68.6, 31.9, 68.3);
        p.curveTo(37.7, 67.2, 44.7, 68.5, 51, 71.9);
        p.curveTo(53.2, 64.5, 53.2, 47, 51, 39.6);
        p.closePath();
        return p;
    }

    /** The leaf catching the light. */
    private static Path2D.Double litLeaf() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(57, 39.6);
        p.curveTo(63.4, 35.9, 70.6, 34.4, 76.6, 35.4);
        p.curveTo(77.9, 35.6, 78.8, 36.7, 78.8, 38);
        p.lineTo(78.8, 66);
        p.curveTo(78.8, 67.5, 77.5, 68.6, 76.1, 68.3);
        p.curveTo(70.3, 67.2, 63.3, 68.5, 57, 71.9);
        p.curveTo(54.8, 64.5, 54.8, 47, 57, 39.6);
        p.closePath();
        return p;
    }

    /**
     * Paints the mark with its centre at ({@code cx}, {@code cy}) and the given width
     * in pixels.
     *
     * The transform is saved and restored rather than applied to the paths, so a
     * caller can draw the mark twice at different sizes without the second one
     * inheriting the first one's scale — the kind of bug that produces a perfect
     * icon and a feature graphic with a mark four times too big.
     */
    private static void paintMark(Graphics2D g, double cx, double cy, double widthPx) {
        double scale = widthPx / MARK_WIDTH;
        double markCx = (MARK_LEFT + MARK_RIGHT) / 2;
        double markCy = (MARK_TOP + MARK_BOTTOM) / 2;

        AffineTransform saved = g.getTransform();
        g.translate(cx - markCx * scale, cy - markCy * scale);
        g.scale(scale, scale);

        g.setColor(PAGE);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SHADOW_ALPHA));
        g.fill(shadowLeaf());
        g.setComposite(AlphaComposite.SrcOver);
        g.fill(litLeaf());

        g.setTransform(saved);
    }

    // ---------------------------------------------------------------------
    // The two images
    // ---------------------------------------------------------------------

    /**
     * The 512x512 listing icon.
     *
     * ARGB so the file is the 32-bit PNG Play asks for, though every pixel is opaque:
     * Play composites the icon onto its own backgrounds and applies its own rounded
     * mask, and an icon with transparent corners of its own gets a doubled, uneven
     * edge where the two roundings disagree.
     */
    private static BufferedImage icon() {
        BufferedImage image =
                new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(image.createGraphics());

        g.setColor(GROUND);
        g.fillRect(0, 0, ICON_SIZE, ICON_SIZE);

        paintMark(g, ICON_SIZE / 2.0, ICON_SIZE / 2.0,
                ICON_SIZE * ICON_MARK_WIDTH_FRACTION);

        g.dispose();
        return image;
    }

    /**
     * The 1024x500 feature graphic: the mark, the name, and the one sentence that is
     * the whole pitch.
     *
     * RGB, not ARGB. Play rejects a feature graphic with an alpha channel.
     *
     * Everything sits well inside the edges because Play crops this image for some
     * placements without saying which, and a wordmark that loses its F on a tablet
     * listing is not a bug anyone sees before it ships.
     */
    private static BufferedImage featureGraphic() throws Exception {
        BufferedImage image = new BufferedImage(
                FEATURE_WIDTH, FEATURE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(image.createGraphics());

        // A gradient rather than the flat ground, because a 1024x500 flat rectangle
        // reads as a placeholder. Both ends are derived from GROUND by a fixed
        // factor, so the brand colour stays the single source and this cannot drift
        // into being a different blue.
        g.setPaint(new GradientPaint(
                0, 0, shade(GROUND, 1.10f),
                FEATURE_WIDTH, FEATURE_HEIGHT, shade(GROUND, 0.86f)));
        g.fillRect(0, 0, FEATURE_WIDTH, FEATURE_HEIGHT);

        double markWidth = 208;
        double markCx = 208;
        paintMark(g, markCx, FEATURE_HEIGHT / 2.0, markWidth);

        Font wordmark = font("source_serif_semibold.ttf", 116f);
        Font tagline = font("work_sans_regular.ttf", 30f);

        int textLeft = 392;
        g.setFont(wordmark);
        FontMetrics wm = g.getFontMetrics();
        g.setFont(tagline);
        FontMetrics tm = g.getFontMetrics();

        // The two lines are centred as one block on the canvas's midline, using the
        // cap height of the wordmark rather than its full ascent: a serif face
        // reserves space above the capitals for accents nothing here uses, and
        // centring on the ascent leaves the block sitting visibly low.
        int gap = 26;
        double capHeight = wm.getAscent() * 0.72;
        double blockHeight = capHeight + gap + tm.getAscent();
        double top = (FEATURE_HEIGHT - blockHeight) / 2;

        g.setFont(wordmark);
        g.setColor(PAGE);
        g.drawString("Folio", textLeft, (int) Math.round(top + capHeight));

        g.setFont(tagline);
        // The tagline is the page colour held back, the same move the mark makes
        // across the fold — one ink at two strengths, not two colours.
        g.setColor(new Color(PAGE.getRed(), PAGE.getGreen(), PAGE.getBlue(), 190));
        g.drawString(
                "Your books never leave your device.",
                textLeft,
                (int) Math.round(top + capHeight + gap + tm.getAscent()));

        g.dispose();
        return image;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** Multiplies a colour's channels, clamped. Used only to derive the gradient. */
    private static Color shade(Color base, float factor) {
        return new Color(
                Math.min(255, Math.round(base.getRed() * factor)),
                Math.min(255, Math.round(base.getGreen() * factor)),
                Math.min(255, Math.round(base.getBlue() * factor)));
    }

    /**
     * Loads one of the app's own typefaces.
     *
     * Not a system font name: {@code new Font("Serif", ...)} resolves to whatever the
     * machine happens to have, so the same command would produce a different feature
     * graphic on a colleague's laptop and nobody would know which one was uploaded.
     * These four files are in the repository and are the faces the app itself sets.
     */
    private static Font font(String file, float size) throws Exception {
        File path = new File(FONT_DIR + file);
        if (!path.isFile()) {
            throw new IllegalStateException(
                    "missing " + path.getPath() + " — run this from the repository root");
        }
        return Font.createFont(Font.TRUETYPE_FONT, path).deriveFont(size);
    }

    private PlayGraphics() {
    }
}
