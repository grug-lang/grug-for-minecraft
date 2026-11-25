package net.grug.minecraft.grug;

/** What a block entity's liquid exports answered a question about the liquid it holds. */
public final class LiquidResponse extends GrugResponse {
    @GrugGenerated("the enum initializer is compiler-generated; its values are plain constants")
    public enum Kind {
        AMOUNT,
        LIQUID,
        NONE
    }

    private Kind kind = null;
    private double amount = 0;
    private String liquid = null;

    public LiquidResponse(String exportName) {
        super(exportName);
    }

    public void answerAmount(double amount) {
        if (beginAnswer("LiquidResponse.answer_amount")) {
            kind = Kind.AMOUNT;
            this.amount = amount;
        }
    }

    public void answerLiquid(String liquid) {
        if (beginAnswer("LiquidResponse.answer_liquid")) {
            kind = Kind.LIQUID;
            this.liquid = liquid;
        }
    }

    public void answerNone() {
        if (beginAnswer("LiquidResponse.answer_none")) {
            kind = Kind.NONE;
        }
    }

    public Kind kind() {
        return kind;
    }

    public double amount() {
        return amount;
    }

    public String liquid() {
        return liquid;
    }
}
