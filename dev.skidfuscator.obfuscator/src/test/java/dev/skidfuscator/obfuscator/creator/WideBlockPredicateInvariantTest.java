package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.predicate.opaque.impl.IntegerBlockOpaquePredicate;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WideBlockPredicateInvariantTest {
    @Test void setKeepsMaterializedWidePredicateLowWordInSync() {
        IntegerBlockOpaquePredicate predicate = new IntegerBlockOpaquePredicate(null);
        SkidBlock block = mock(SkidBlock.class);
        predicate.getLong(block); // force the independent 64-bit cache to exist first
        int replacement = 0x13579BDF;
        predicate.set(block, replacement);
        assertEquals(replacement, predicate.get(block));
        assertEquals(replacement, (int) predicate.getLong(block),
                "low 32 bits of the wide flow seed must always equal the int predicate");
    }
}
