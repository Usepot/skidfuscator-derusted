package dev.skidfuscator.obfuscator.predicate.opaque;

import dev.skidfuscator.obfuscator.skidasm.SkidGroup;

public interface MethodOpaquePredicate extends OpaquePredicate<SkidGroup> {
    int getPublic();

    int getPrivate();

    /**
     * 64-bit method seed. The low 32 bits equal {@link #getPublic()} /
     * {@link #getPrivate()} so the existing int reconstruction is unchanged; the
     * high 32 bits carry the extra entropy threaded across the call boundary only
     * when {@code seed.wide} is enabled.
     */
    long getPublicLong();

    long getPrivateLong();
}
