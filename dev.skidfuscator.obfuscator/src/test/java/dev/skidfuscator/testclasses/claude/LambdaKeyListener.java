package dev.skidfuscator.testclasses.claude;

// Deliberately unannotated: @FunctionalInterface is optional in Java.
public interface LambdaKeyListener {
    boolean keyEvent(int key);
    default int unrelatedDefault() { return 7; }
    static int unrelatedStatic() { return 9; }
}
