package nurgling.watch;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Shrinks a captured frame into a JPEG small enough to attach to a webhook message. */
public final class ScreenshotEncoder {
    public static final int MAX_WIDTH = 1280;
    public static final int MAX_BYTES = 1_500_000;

    private ScreenshotEncoder() {
    }

    public static byte[] encode(BufferedImage src) throws IOException {
        return encode(src, MAX_WIDTH, MAX_BYTES);
    }

    /**
     * Scales down to {@code maxWidth} (never up) and lowers JPEG quality, then width, until the
     * result fits {@code maxBytes}. Alpha is flattened onto black, which is what the game shows
     * behind an unloaded scene.
     */
    public static byte[] encode(BufferedImage src, int maxWidth, int maxBytes) throws IOException {
        if (src == null || src.getWidth() < 1 || src.getHeight() < 1)
            throw new IOException("empty image");
        int width = Math.min(src.getWidth(), maxWidth);
        byte[] best = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            BufferedImage scaled = scale(src, width);
            for (float q : new float[]{0.82f, 0.65f, 0.45f}) {
                best = jpeg(scaled, q);
                if (best.length <= maxBytes)
                    return best;
            }
            width = Math.max(160, width * 3 / 4);
        }
        if (best.length > maxBytes)
            throw new IOException("image still " + best.length + " bytes after shrinking");
        return best;
    }

    private static BufferedImage scale(BufferedImage src, int width) {
        int height = Math.max(1, (int) Math.round(src.getHeight() * (width / (double) src.getWidth())));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(buf)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return buf.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
