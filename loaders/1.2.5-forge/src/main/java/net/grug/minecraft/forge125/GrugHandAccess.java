package net.grug.minecraft.forge125;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.ItemRenderer;

import java.lang.reflect.Field;

/**
 * Reads the equip progress 1.2.5's first-person item renderer keeps private.
 *
 * <p>1.2.5 has no access widener or Mixin, and {@code ItemRenderer} keeps the two values it
 * interpolates the hand's height with private. Reflection is the one route to them that does not
 * patch the game classes. This is a loader class and stays in the loader's own package, because a
 * class in the game's package would be reobfuscated to the jar root.
 *
 * <p>A capture waits while the hand is still easing into place, so that two runs capture the same
 * pose rather than whatever point of the rise their ticks reached (#961). The values are the ones
 * {@code updateEquippedItem} writes and {@code renderItemInFirstPerson} draws with, not a second
 * copy of that state.
 */
public final class GrugHandAccess {

    private static final Field EQUIPPED_PROGRESS = field("equippedProgress", "c");
    private static final Field PREV_EQUIPPED_PROGRESS = field("prevEquippedProgress", "d");

    @GrugGenerated("utility class: never instantiated")
    private GrugHandAccess() {}

    /**
     * The field by its MCP name, or by the name the pinned 1.2.5 snapshot reobfuscates it to. The
     * runtime jar is launched reobfuscated, so the MCP name is not there; a development run that
     * kept the names resolves the first one. The pairs are taken from the pinned snapshot's own
     * {@code client.srg}, which names the two fields this class needs.
     */
    @GrugGenerated(
            "reflection: the runtime jar carries only the obfuscated name, so the MCP lookup's"
                    + " hit and the throws cannot be reached in a run")
    private static Field field(String mcpName, String obfuscatedName) {
        try {
            Field field;
            try {
                field = ItemRenderer.class.getDeclaredField(mcpName);
            } catch (NoSuchFieldException missing) {
                field = ItemRenderer.class.getDeclaredField(obfuscatedName);
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @GrugGenerated("reflection: the field is accessible, so its catch cannot fire")
    private static float read(Field field, ItemRenderer renderer) {
        try {
            return field.getFloat(renderer);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The height the renderer last drew the hand at, 0 for an item that just changed. */
    public static float equippedProgress(ItemRenderer renderer) {
        return read(EQUIPPED_PROGRESS, renderer);
    }

    /** The height the tick before it drew the hand at. */
    public static float prevEquippedProgress(ItemRenderer renderer) {
        return read(PREV_EQUIPPED_PROGRESS, renderer);
    }
}
