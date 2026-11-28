package net.grug.minecraft.grug;

import java.util.Random;

/**
 * The handle {@code Random.new} and {@code Random.new_seeded} return: one random stream the mod
 * owns.
 *
 * <p>It is Java's own {@link Random} and nothing else, so the sequence a seed gives is the one the
 * game's Java specifies and the reference mod's {@code new Random()} draws. A draw from the level's
 * random instead moves the sequence the world's own behaviour depends on, which is why a mod that
 * wants a colour or a pick keeps one of these.
 */
final class GrugRandom {

    private final Random random;

    GrugRandom(Random random) {
        this.random = random;
    }

    /** The stream's next value, from 0 up to but not including 1, as a float. */
    double nextFloat() {
        return random.nextFloat();
    }

    /** The stream's next value, from 0 up to but not including 1, as a double. */
    double nextDouble() {
        return random.nextDouble();
    }

    /** The stream's next whole number, from 0 up to but not including {@code bound}. */
    int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
