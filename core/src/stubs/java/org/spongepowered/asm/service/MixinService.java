package org.spongepowered.asm.service;

/**
 * Compile-time stand-in for Mixin's service locator. Tests run against it, where no Mixin is
 * booted, so its service finds no resources.
 */
public final class MixinService {
    private MixinService() {}

    public static IMixinService getService() {
        return name -> null;
    }
}
