package org.spongepowered.asm.mixin.extensibility;

import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * Compile-time stand-in for Mixin's config plugin interface, with the signatures it has in every
 * Mixin from 0.8.4 to 0.8.7, so core compiles GrugMixinPlugin without Mixin on any class path. The
 * game brings the real one; this interface is never packaged.
 */
public interface IMixinConfigPlugin {
    void onLoad(String mixinPackage);

    String getRefMapperConfig();

    boolean shouldApplyMixin(String targetClassName, String mixinClassName);

    void acceptTargets(Set<String> myTargets, Set<String> otherTargets);

    List<String> getMixins();

    void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo);

    void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo);
}
