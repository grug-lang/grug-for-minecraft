package net.grug.minecraft.grug;

/**
 * The payload of a {@code Screenshot} entity: a tolerance percent for the comparison {@code
 * Screenshot.equals} runs.
 *
 * <p>Like {@link GrugOption}, this is a record replaced in place of the object stored under the
 * entity id ({@code Screenshot.tolerance} goes through {@link Grug#addEntityWithId}), rather than a
 * mutable holder.
 */
public record GrugScreenshot(double tolerancePercent) {}
