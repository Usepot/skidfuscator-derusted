package dev.skidfuscator.nativetoolchain;

import dev.skidfuscator.nativeir.NativeModule;

/** Converts verified target-neutral native IR to canonical UTF-8 LLVM IR. */
@FunctionalInterface
public interface LlvmIrEmitter {
    byte[] emit(NativeModule module);
}
