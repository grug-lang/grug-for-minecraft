package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Covers the one-texel border that turns a stitched sprite into a sprite the atlas cannot leak into
 * (#919).
 *
 * <p>The expected padding is written out independently of the class under test: a frame's pixel is
 * named by its own row and column, and the padded result is expected to hand back the frame's
 * nearest edge pixel for every position outside it. That is the rule the renderer needs, so any
 * other border would pass a test that only compared against a second copy of the implementation.
 */
class GrugSpritePaddingTest {

    @Test
    void aSpriteGrowsByOneTexelOnEachSide() {
        assertEquals(18, GrugSpritePadding.paddedWidth(16));
        assertEquals(18, GrugSpritePadding.paddedHeight(16));
        assertEquals(3, GrugSpritePadding.paddedWidth(1));
        assertEquals(5, GrugSpritePadding.paddedWidth(3));
        assertEquals(7, GrugSpritePadding.paddedHeight(5));
    }

    @Test
    void aSingleTexelSpriteIsNineCopiesOfIt() {
        // The smallest sprite there is: its own edge is all of it, so every padded texel is it.
        int colour = 0xff123456;
        int[] padded = GrugSpritePadding.padded(1, 1, new int[] {colour});
        assertEquals(9, padded.length);
        for (int pixel : padded) assertEquals(colour, pixel);
    }

    @Test
    void theFrameIsUnchangedInsideTheBorder() {
        // A 3x2 frame of distinct pixels: each one is found one texel in from the top left, and
        // the row-major order is preserved.
        int[] frame = {1, 2, 3, 4, 5, 6};
        int[] padded = GrugSpritePadding.padded(3, 2, frame);
        assertEquals(5 * 4, padded.length);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(frame[y * 3 + x], padded[(y + 1) * 5 + x + 1]);
            }
        }
    }

    @Test
    void everyBorderTexelIsTheFramePixelNearestToIt() {
        // A 4x3 frame whose pixels are all different, so a border texel taken from anywhere but the
        // nearest edge is caught.
        int[] frame = new int[12];
        for (int i = 0; i < frame.length; i++) frame[i] = 100 + i;
        int[] padded = GrugSpritePadding.padded(4, 3, frame);
        int width = GrugSpritePadding.paddedWidth(4);
        int height = GrugSpritePadding.paddedHeight(3);
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(Math.max(y - 1, 0), 2);
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(Math.max(x - 1, 0), 3);
                assertEquals(
                        frame[sourceY * 4 + sourceX],
                        padded[y * width + x],
                        "padded pixel " + x + "," + y);
            }
        }
    }

    @Test
    void aTransparentEdgeIsRepeatedToo() {
        // Fire's frame is mostly transparent at its edge, and the border has to carry that rather
        // than an opaque colour: the point is the sprite's own texel, whatever it holds. This frame
        // is opaque along its top edge and transparent along its bottom one, and the border keeps
        // each side's own colour, corners included.
        int opaque = 0xff102030;
        int[] frame = {opaque, opaque, 0, 0};
        int[] expected = {
            opaque, opaque, opaque, opaque, opaque, opaque, opaque, opaque, 0, 0, 0, 0, 0, 0, 0, 0,
        };
        assertArrayEquals(expected, GrugSpritePadding.padded(2, 2, frame));
    }

    @Test
    void everyRowOfTheResultIsAsWideAsThePaddedFrame() {
        // The renderer hands the whole result to one upload, so a row of the wrong length would
        // shear the atlas rather than fail; check the shape directly.
        int[] padded = GrugSpritePadding.padded(5, 7, new int[35]);
        assertEquals(
                GrugSpritePadding.paddedWidth(5) * GrugSpritePadding.paddedHeight(7),
                padded.length);
    }

    @Test
    void aSpriteWithNoTexelsIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugSpritePadding.padded(0, 4, new int[0]));
        assertThrows(
                IllegalArgumentException.class, () -> GrugSpritePadding.padded(4, 0, new int[0]));
        assertThrows(
                IllegalArgumentException.class, () -> GrugSpritePadding.padded(-1, 4, new int[4]));
        assertThrows(
                IllegalArgumentException.class, () -> GrugSpritePadding.padded(4, -1, new int[4]));
    }

    @Test
    void aFrameShorterThanItsSizeIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugSpritePadding.padded(4, 4, new int[15]));
    }

    @Test
    void aFrameLongerThanItsSizeIsReadByItsFirstPixels() {
        // A sprite sheet frame is a rectangle of a larger image, and the caller has already cut it
        // to size; a longer array is read by its first width * height pixels and nothing else.
        int[] frame = new int[9];
        for (int i = 0; i < frame.length; i++) frame[i] = 200 + i;
        int[] fromExactly =
                GrugSpritePadding.padded(3, 2, new int[] {200, 201, 202, 203, 204, 205});
        int[] fromLonger = GrugSpritePadding.padded(3, 2, frame);
        assertArrayEquals(fromExactly, fromLonger);
    }
}
