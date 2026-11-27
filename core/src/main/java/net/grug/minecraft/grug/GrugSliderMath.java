package net.grug.minecraft.grug;

/**
 * The pure arithmetic of a slider: where the handle sits on its track for a value, which value a
 * track fraction names, and the message drawn across the track.
 *
 * <p>The reference's slider is the rule: the handle sits at {@code (value - min) / (max - min)},
 * the value is {@code min + fraction * (max - min)}, and the message is the widget's text followed
 * by the value rounded to tenths, {@code Math.round(10.0 * value) / 10.0}. A value outside the
 * range is clamped into it, because the reference is a vanilla slider, whose {@code setValue}
 * clamps, and this API's value is the value the handle is drawn at. The drawing clamp is then only
 * a guard against a range that is not usable, which {@link #rangeProblem} refuses anyway.
 */
public final class GrugSliderMath {

    @GrugGenerated("utility class: never instantiated")
    private GrugSliderMath() {}

    /** Why a slider cannot have this range, or null when it can. */
    public static String rangeProblem(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return "The min and the max have to be finite, and they were "
                    + Double.toString(min)
                    + " and "
                    + Double.toString(max)
                    + ".";
        }
        if (!(max > min)) {
            return "The max has to be greater than the min, and they were "
                    + Double.toString(min)
                    + " and "
                    + Double.toString(max)
                    + ".";
        }
        return null;
    }

    /** The fraction of the track the value names, unclamped. */
    public static double fraction(double value, double min, double max) {
        return (value - min) / (max - min);
    }

    /** The value a fraction of the track names, unclamped. */
    public static double value(double fraction, double min, double max) {
        return min + fraction * (max - min);
    }

    /**
     * The value brought into the slider's range, which is the value the handle is drawn at. A range
     * that is not usable is left alone, because {@link #rangeProblem} refuses one before any value
     * is applied to it.
     */
    public static double clamp(double value, double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || !(max > min)) return value;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * The fraction the game's own slider draws its handle at, which is clamped to the track and
     * answers 0 for a fraction that is not a number.
     */
    public static double drawnFraction(double fraction) {
        if (!(fraction > 0.0)) return 0.0;
        return Math.min(fraction, 1.0);
    }

    /** The message drawn across a slider's track: its text and the value rounded to tenths. */
    public static String label(String text, double value) {
        return text + ": " + Double.toString(Math.round(10.0 * value) / 10.0);
    }
}
