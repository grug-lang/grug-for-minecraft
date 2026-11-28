package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

/**
 * What a block entity's liquid exports answered, and the canonical names a liquid may have.
 *
 * <p>A liquid is a string, the still liquid's canonical name, and this class is where the host
 * resolves one: {@code minecraft:flowing_water} is the same liquid as {@code minecraft:water}, so a
 * script never sees a flowing name and two offers of the same liquid compare equal. Only the
 * vanilla liquids exist until a grug block can declare itself one (#1626 lands the oil blocks), and
 * that declaration is what will extend this table.
 *
 * <p>The state itself is the script's: the host holds no liquid. It asks the block entity's exports
 * and answers what they say, as {@link GrugSpecialInventory} does for an item.
 */
public final class GrugLiquids {

    /** What an offer or a take answers when the block entity has no export for it. */
    static final double NO_EXPORT = -1;

    @GrugGenerated("utility class: never instantiated")
    private GrugLiquids() {}

    /**
     * The still liquid's canonical name, or null when {@code name} is no liquid. The caller reports
     * the null, so a typo in a script names its own call rather than silently moving nothing.
     */
    public static String canonical(String name) {
        if (name.equals("minecraft:water") || name.equals("minecraft:flowing_water")) {
            return "minecraft:water";
        }
        if (name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava")) {
            return "minecraft:lava";
        }
        return null;
    }

    /**
     * Offers liquid to a block entity through its {@code offer_liquid} export.
     *
     * @return how much of {@code amount} it took, 0 on a refusal, or {@link #NO_EXPORT}
     */
    static double offer(
            Object blockEntity, String liquid, double amount, double fromSide, boolean fill) {
        LiquidResponse response = callOffer(blockEntity, liquid, amount, fromSide, fill);
        return takenAmount(response, "offer_liquid");
    }

    /**
     * Asks a block entity for liquid through its {@code take_liquid} export.
     *
     * @return how much it gave or would give, 0 on a refusal, or {@link #NO_EXPORT}
     */
    static double take(Object blockEntity, double amount, boolean take) {
        LiquidResponse response = callTake(blockEntity, amount, take);
        return takenAmount(response, "take_liquid");
    }

    /**
     * Offers liquid to one block of a store through its {@code offer_liquid_here} export, the
     * walk's continuation rather than its start.
     *
     * @return how much of {@code amount} it took, 0 on a refusal, or {@link #NO_EXPORT}
     */
    static double offerHere(
            Object blockEntity, String liquid, double amount, double fromSide, boolean fill) {
        LiquidResponse response = callOfferHere(blockEntity, liquid, amount, fromSide, fill);
        return takenAmount(response, "offer_liquid_here");
    }

    /**
     * Asks one block of a store for liquid through its {@code take_liquid_here} export, the walk's
     * continuation rather than its start.
     *
     * @return how much it gave or would give, 0 on a refusal, or {@link #NO_EXPORT}
     */
    static double takeHere(Object blockEntity, double amount, boolean take) {
        LiquidResponse response = callTakeHere(blockEntity, amount, take);
        return takenAmount(response, "take_liquid_here");
    }

    /** What an offer or a take of {@code exportName} answers, checked as the host can use it. */
    private static double takenAmount(LiquidResponse response, String exportName) {
        if (response == null) {
            return NO_EXPORT;
        }
        if (response.kind() != LiquidResponse.Kind.AMOUNT) {
            reportWrongAnswer(exportName, "an amount");
            return 0;
        }
        double given = response.amount();
        if (!Double.isFinite(given) || given < 0) {
            reportWrongAnswer(exportName, "a finite amount of 0 or more");
            return 0;
        }
        return given;
    }

    /** The liquid a block entity holds, canonical and normalised, or null when it holds none. */
    static String getLiquid(Object blockEntity) {
        LiquidResponse response = callGetLiquid(blockEntity);
        if (response == null) {
            return null;
        }
        switch (response.kind()) {
            case NONE:
                return null;
            case LIQUID:
                String canonical = canonical(response.liquid());
                if (canonical == null) {
                    reportWrongAnswer("get_liquid", "a liquid name");
                    return null;
                }
                return canonical;
            default:
                reportWrongAnswer("get_liquid", "a liquid name or none");
                return null;
        }
    }

    /** The millibuckets a block entity holds, or 0 when it has no {@code get_liquid_amount}. */
    static double amount(Object blockEntity) {
        LiquidResponse response = callAmount(blockEntity, "get_liquid_amount", false);
        if (response == null) {
            return 0;
        }
        if (response.kind() != LiquidResponse.Kind.AMOUNT) {
            reportWrongAnswer("get_liquid_amount", "an amount");
            return 0;
        }
        return response.amount();
    }

    /** The millibuckets a block entity holds at most, or 0 when it has no capacity export. */
    static double capacity(Object blockEntity) {
        LiquidResponse response = callAmount(blockEntity, "get_liquid_capacity", true);
        if (response == null) {
            return 0;
        }
        if (response.kind() != LiquidResponse.Kind.AMOUNT) {
            reportWrongAnswer("get_liquid_capacity", "an amount");
            return 0;
        }
        return response.amount();
    }

    private static LiquidResponse callOffer(
            Object blockEntity, String liquid, double amount, double fromSide, boolean fill) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse("offer_liquid");
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        if (!ExportFns.BlockEntity_offer_liquid(
                handle, liquid, amount, fromSide, fill, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredZero();
    }

    private static LiquidResponse callTake(Object blockEntity, double amount, boolean take) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse("take_liquid");
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        if (!ExportFns.BlockEntity_take_liquid(handle, amount, take, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredZero();
    }

    private static LiquidResponse callOfferHere(
            Object blockEntity, String liquid, double amount, double fromSide, boolean fill) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse("offer_liquid_here");
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        if (!ExportFns.BlockEntity_offer_liquid_here(
                handle, liquid, amount, fromSide, fill, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredZero();
    }

    private static LiquidResponse callTakeHere(Object blockEntity, double amount, boolean take) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse("take_liquid_here");
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        if (!ExportFns.BlockEntity_take_liquid_here(handle, amount, take, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredZero();
    }

    private static LiquidResponse callGetLiquid(Object blockEntity) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse("get_liquid");
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        if (!ExportFns.BlockEntity_get_liquid(handle, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredNone();
    }

    private static LiquidResponse callAmount(
            Object blockEntity, String exportName, boolean capacity) {
        long handle = handle(blockEntity);
        if (handle == 0) {
            return null;
        }
        LiquidResponse response = new LiquidResponse(exportName);
        long responseId = GrugEntities.addEntity(GrugEntityType.LiquidResponse, response);
        boolean ran =
                capacity
                        ? ExportFns.BlockEntity_get_liquid_capacity(handle, responseId)
                        : ExportFns.BlockEntity_get_liquid_amount(handle, responseId);
        if (!ran) {
            response.close();
            return null;
        }
        return response.finish() ? response : answeredZero();
    }

    private static long handle(Object blockEntity) {
        return GrugCore.getAdapter().getGrugEntityHandle(blockEntity);
    }

    /** What a request counts as after the export returned without answering, the err reported. */
    private static LiquidResponse answeredZero() {
        LiquidResponse response = new LiquidResponse("liquid");
        response.answerAmount(0);
        return response;
    }

    private static LiquidResponse answeredNone() {
        LiquidResponse response = new LiquidResponse("liquid");
        response.answerNone();
        return response;
    }

    private static void reportWrongAnswer(String exportName, String wanted) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                exportName
                        + ": The export answered "
                        + "with the wrong kind of answer, and this question wants "
                        + wanted
                        + ".");
    }
}
