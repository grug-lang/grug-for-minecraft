package net.grug.minecraft.grug;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that is deliberately excluded from coverage, such as developer-only interaction.
 *
 * <p>JaCoCo drops methods and classes carrying an annotation whose name contains "Generated", so
 * this marker keeps "Generated" in its name and CLASS retention. Note that {@code
 * javax.annotation.processing.Generated} does not work: its SOURCE retention means it never reaches
 * the bytecode.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface GrugGenerated {
    /** Why this code is excluded from coverage. */
    String value();
}
