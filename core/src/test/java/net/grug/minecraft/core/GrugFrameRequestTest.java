package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the frame request every loader asks before it draws. See {@link GrugFrameRequest} and
 * #770.
 *
 * <p>The loaders ask from their own render path, which no unit test reaches, so the transitions are
 * measured here; the runner's own two calls are measured by {@code GrugTestRunnerTest}.
 */
class GrugFrameRequestTest {

    @AfterEach
    void restoreFrames() {
        // The flag is static, so a test that left it clear would make the next one's subject
        // depend on the order the tests ran in.
        GrugFrameRequest.endTest();
    }

    @Test
    void wantsFramesUntilATestAsksForOne() {
        assertTrue(GrugFrameRequest.wanted());
        GrugFrameRequest.beginTest();
        assertFalse(GrugFrameRequest.wanted());
    }

    @Test
    void wantsAFrameOnceTheTestRequestsOne() {
        GrugFrameRequest.beginTest();
        GrugFrameRequest.request();
        assertTrue(GrugFrameRequest.wanted());
    }

    @Test
    void wantsFramesAgainWhenTheTestEnds() {
        GrugFrameRequest.beginTest();
        GrugFrameRequest.endTest();
        assertTrue(GrugFrameRequest.wanted());
    }
}
