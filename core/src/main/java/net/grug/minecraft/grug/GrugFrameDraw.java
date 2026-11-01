package net.grug.minecraft.grug;

/**
 * One thing a block entity's frame pass drew: a stack, or a model's faces.
 *
 * <p>A frame pass draws in the order the script called it, and a later draw covers an earlier one
 * where the two overlap, so the two kinds share a list and are handed back in that order rather
 * than grouped by kind. A loader draws what it recognises and has nothing to do with the rest.
 */
public interface GrugFrameDraw {}
