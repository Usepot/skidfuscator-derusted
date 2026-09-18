package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;
import java.io.Serializable;

public class LambdaSamClazz implements TestRun {
    private static boolean renamedBody(int key) { return key == 42; }
    private static boolean invoke(LambdaKeyListener listener, int key) {
        return listener.keyEvent(key);
    }
    @Override public void run() {
        int capture = 42;
        LambdaKeyListener plain = key -> key == capture;
        LambdaKeyListener reference = LambdaSamClazz::renamedBody;
        InheritedLambdaKeyListener inherited = key -> key == 42;
        LambdaKeyListener serializable = (LambdaKeyListener & Serializable) key -> key == 42;
        for (LambdaKeyListener listener : new LambdaKeyListener[]{plain, reference, inherited, serializable}) {
            if (!invoke(listener, 42) || invoke(listener, 1) || listener.unrelatedDefault() != 7)
                throw new IllegalStateException("Lambda SAM dispatch changed");
        }
    }
}
