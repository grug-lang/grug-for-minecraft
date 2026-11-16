package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The decisions behind which items a loader lists, apart from the game, so a unit test enters every
 * arm (#892). The reference's list comes from the creative tabs, and the operator tab shows its
 * items only to a player who may use game master blocks, so a player who may not never sees them.
 */
public final class GrugItemList {
    @GrugGenerated("utility class: never instantiated")
    private GrugItemList() {}

    /**
     * A copy of {@code all} without the items that only the operator tab lists, in the same order.
     */
    public static <T> List<T> withoutOperatorOnly(List<T> all, Set<T> operatorOnly) {
        List<T> kept = new ArrayList<>();
        for (T item : all) {
            if (!operatorOnly.contains(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * The list a player sees: every item when they may use operator items, else the filtered one.
     */
    public static <T> List<T> listedFor(
            boolean mayUseOperatorItems, List<T> all, List<T> filtered) {
        return mayUseOperatorItems ? all : filtered;
    }

    /**
     * Whether a player may use the operator tab's items: the game must let them use game master
     * blocks, and where this process holds the operator list (singleplayer) the host's own notion
     * of an operator must agree, because a script's revoke of the world's owner changes that and
     * not the game's permission level. A client joined to a server has only the game's answer.
     */
    public static boolean mayUseOperatorItems(
            boolean gameMasterBlocks, boolean holdsOperatorList, BooleanSupplier operator) {
        return gameMasterBlocks && (!holdsOperatorList || operator.getAsBoolean());
    }
}
