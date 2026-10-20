package net.fabricmc.loader.api.entrypoint;

/** Compile-time stand-in for Fabric Loader's earliest entrypoint. Never packaged. */
public interface PreLaunchEntrypoint {
    void onPreLaunch();
}
