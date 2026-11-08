package net.grug.minecraft.core;

/**
 * The arithmetic an inventory click applies when it merges a carried stack into the slot under the
 * pointer.
 *
 * <p>It lives in core, apart from any loader's menu, so a loader that applies such a click itself
 * shares the one rule and a unit test can pin it. Alpha's click is the one that needs it today, and
 * it runs on the server of a joined client, which only the dispatch-only multiplayer job reaches.
 */
public final class GrugMenuClick {
    /**
     * The stack size an ordinary container slot accepts. Every inventory a grug GUI binds a slot to
     * is an ordinary container, so a slot takes no more than this, however large the stack a
     * content packet carried.
     */
    public static final int MAX_STACK_SIZE = 64;

    private GrugMenuClick() {}

    /**
     * How many items a merge moves out of the carried stack and into the slot under the pointer:
     * the whole carried stack for a left click ({@code button} 0) and one item for a right click,
     * capped by the room the slot and the carried stack's own maximum leave.
     *
     * <p>It answers 0 when nothing fits, which is a full slot, and then the click must leave the
     * inventory alone. Clamping there also keeps a slot that somehow holds more than its stack's
     * maximum from moving a negative amount.
     *
     * @param carried how many items the carried stack holds
     * @param inSlot how many items the slot under the pointer holds
     * @param stackLimit the most items one stack of the carried item holds, as the game answers
     */
    public static int mergeAmount(int button, int carried, int inSlot, int stackLimit) {
        int amount = button == 0 ? carried : 1;
        int room = MAX_STACK_SIZE - inSlot;
        if (amount > room) {
            amount = room;
        }
        int stackRoom = stackLimit - inSlot;
        if (amount > stackRoom) {
            amount = stackRoom;
        }
        return Math.max(amount, 0);
    }
}
