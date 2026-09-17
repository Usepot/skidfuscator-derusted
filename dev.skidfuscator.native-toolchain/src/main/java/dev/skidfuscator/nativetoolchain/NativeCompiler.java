package dev.skidfuscator.nativetoolchain;

import java.io.IOException;

/** Stable integration seam for canonical LLVM emission and SkidLLVM invocation. */
@FunctionalInterface
public interface NativeCompiler {
    NativeCompilationResult compile(NativeCompilationRequest request) throws IOException, InterruptedException;
}
