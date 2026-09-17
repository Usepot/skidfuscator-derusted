package dev.skidfuscator.nativeir;

/** A named SSA definition. */
public interface NativeValue {
    String id();

    NativeType type();
}
