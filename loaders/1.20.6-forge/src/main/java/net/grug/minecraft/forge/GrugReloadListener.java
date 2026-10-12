package net.grug.minecraft.forge;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reports each resource reload's apply stage, so a test's {@code reload_count} can rise when the
 * loader has finished reloading.
 *
 * <p>The future {@code reloadResourcePacks} returns completes only when the LoadingOverlay it
 * installs renders after the reload is done, and this loader clears that overlay immediately so the
 * game is not paused behind a blue screen. The reload itself still runs, so the finish is reported
 * from a listener instead: listeners' apply stages run in registration order, and this one is
 * registered after the vanilla listeners that upload the atlases.
 *
 * <p>Each call takes the batch of paths its own reload was started for, which is the batch {@link
 * GrugModLoader#pollReloadBatch()} queued when the reload started. The report itself is deferred to
 * the render tick's end, so the frame a test captures next has the reload in it.
 */
public final class GrugReloadListener implements PreparableReloadListener {
    @Override
    public CompletableFuture<Void> reload(
            PreparationBarrier barrier,
            ResourceManager resourceManager,
            ProfilerFiller prepareProfiler,
            ProfilerFiller applyProfiler,
            Executor backgroundExecutor,
            Executor gameExecutor) {
        String[] batch = GrugModLoader.pollReloadBatch();
        return CompletableFuture.completedFuture(Unit.INSTANCE)
                .thenCompose(barrier::wait)
                .thenRunAsync(() -> GrugModLoader.markReloadApplied(batch), gameExecutor);
    }
}
