package nurgling.watch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which render modes Village Watch refuses screenshots in. Only the decision is tested here; whether
 * a frame in the normal mode is a real picture needs a live client (docs/village-watch.md).
 */
class MapScreenshotRendererTest {

    @Test
    void normalRenderingIsNotRefused() {
        assertNull(MapScreenshotProvider.rendererReason(false, false));
    }

    @Test
    void smoothRendererIsRefusedWithAReason() {
        String why = MapScreenshotProvider.rendererReason(false, true);
        assertNotNull(why);
        assertTrue(why.contains("smooth renderer"), why);
    }

    @Test
    void frameBridgeIsRefusedWithAReason() {
        String why = MapScreenshotProvider.rendererReason(true, false);
        assertNotNull(why);
        assertTrue(why.contains("frame bridge"), why);
        assertTrue(MapScreenshotProvider.rendererReason(true, true).contains("frame bridge"));
    }
}
