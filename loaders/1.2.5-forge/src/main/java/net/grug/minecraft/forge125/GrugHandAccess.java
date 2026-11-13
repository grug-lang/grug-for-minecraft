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

    @GrugGenerated("utility class: never instantiated")
    private GrugHandAccess() {}

    /**
     * The field by its MCP name, or by the name the pinned 1.2.5 snapshot reobfuscates it to. The
     * game this loader launches carries the reobfuscated name, so that is the one that resolves; a
     * development run that kept the MCP names resolves the first. The pairs are taken from the
     * pinned snapshot's own {@code client.srg}, which names the two fields this class needs, and
     * the build checks the literals against it, because a string carries no reference that
     * reobfuscation could follow.
     *
     * <p>The lookup runs on every read and holds no branch, for the coverage gate (#961). A class
     * initializer that ran in a dropped segment is credited for itself and for nothing it calls,
     * and a test of another mod reaches this class first, so a lookup cached in a static field
     * would run once, outside the counted segments. Here the launched game's miss takes the handler
     * of {@link #lookup} and the second lookup behind it in every counted test that asks.
     */
    private static Field field(String mcpName, String obfuscatedName) {
        try {
            return lookup(mcpName);
        } catch (IllegalStateException missing) {
            return lookup(obfuscatedName);
        }
    }

    private static Field lookup(String name) {
        try {
            Field found = ItemRenderer.class.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException("ItemRenderer carries no field " + name, missing);
        }
    }

    @GrugGenerated("reflection: field() makes every field accessible, so read's catch cannot fire")
    private static float read(Field field, ItemRenderer renderer) {
        try {
            return field.getFloat(renderer);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The height the renderer last drew the hand at, 0 for an item that just changed. */
    public static float equippedProgress(ItemRenderer renderer) {
        return read(field("equippedProgress", "c"), renderer);
    }

    /** The height the tick before it drew the hand at. */
    public static float prevEquippedProgress(ItemRenderer renderer) {
        return read(field("prevEquippedProgress", "d"), renderer);
    }
}
