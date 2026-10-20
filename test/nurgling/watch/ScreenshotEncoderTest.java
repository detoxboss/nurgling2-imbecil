package nurgling.watch;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotEncoderTest {
    private static BufferedImage rgba(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 90, 30));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.RED);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return img;
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void output_is_a_decodable_jpeg() throws IOException {
        byte[] out = ScreenshotEncoder.encode(rgba(640, 360));
        assertEquals((byte) 0xFF, out[0]);
        assertEquals((byte) 0xD8, out[1]);
        BufferedImage back = decode(out);
        assertEquals(640, back.getWidth());
        assertEquals(360, back.getHeight());
    }

    @Test
    void wide_frames_are_scaled_down_keeping_the_aspect_ratio() throws IOException {
        BufferedImage back = decode(ScreenshotEncoder.encode(rgba(2560, 1440)));
        assertEquals(ScreenshotEncoder.MAX_WIDTH, back.getWidth());
        assertEquals(720, back.getHeight());
    }

    @Test
    void small_frames_are_never_scaled_up() throws IOException {
        assertEquals(200, decode(ScreenshotEncoder.encode(rgba(200, 100))).getWidth());
    }

    @Test
    void noisy_frames_are_shrunk_until_they_fit_the_byte_budget() throws IOException {
        BufferedImage noise = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(7);
        for (int y = 0; y < 720; y++)
            for (int x = 0; x < 1280; x++)
                noise.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        byte[] out = ScreenshotEncoder.encode(noise, 1280, 300_000);
        assertTrue(out.length <= 300_000, "size " + out.length);
        assertTrue(decode(out).getWidth() <= 1280);
    }

    @Test
    void an_empty_image_is_an_error_not_an_attachment() {
        assertThrows(IOException.class, () -> ScreenshotEncoder.encode(null));
    }
}
