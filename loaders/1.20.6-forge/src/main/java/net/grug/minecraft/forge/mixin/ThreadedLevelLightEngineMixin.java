package net.grug.minecraft.forge.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;

import net.grug.minecraft.forge.GrugLightEngineState;
import net.minecraft.server.level.ThreadedLevelLightEngine;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers what the integrated server's light engine still has to do, for the settle answer.
 *
 * <p>The engine's public {@code hasLightWork} counts only its propagation queues. The block and
 * section checks it has taken sit in a private list, at most a thousand of which run per batch with
 * one propagation run between batches, and the flag that says a batch is already scheduled on the
 * light thread is private too. So the engine answers idle while a rebuild's light is still waiting
 * in that list, and a settle answer built on the public answer alone lets a capture read a client
 * that has not been told about the room at all. See #686.
 *
 * <p>The list's own element type mentions a private nested enum, so the shadow is typed by its
 * erasure: the descriptor is the only thing Mixin reads.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin implements GrugLightEngineState {

    @Shadow @Final private ObjectList<?> lightTasks;

    @Shadow private AtomicBoolean scheduled;

    @Override
    public boolean grug$hasQueuedLightTasks() {
        return !this.lightTasks.isEmpty();
    }

    @Override
    public boolean grug$isUpdateScheduled() {
        return this.scheduled.get();
    }
}
