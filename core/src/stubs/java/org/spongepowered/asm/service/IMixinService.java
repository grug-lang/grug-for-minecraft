package org.spongepowered.asm.service;

import java.io.InputStream;

/** Compile-time stand-in for Mixin's service, of which GrugMixinPlugin uses the resource lookup. */
public interface IMixinService {
    InputStream getResourceAsStream(String name);
}
