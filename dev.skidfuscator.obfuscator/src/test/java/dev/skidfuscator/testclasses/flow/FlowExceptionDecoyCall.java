package dev.skidfuscator.testclasses.flow;

import dev.skidfuscator.annotations.Exclude;
import dev.skidfuscator.testclasses.TestRun;

public class FlowExceptionDecoyCall implements TestRun {
    @Override
    public void run() {
        final int value = decoySeedTarget(7);
        exec(value);
        execReal(value);
    }

    public boolean exec(int value) {
        return value != 12345;
    }

    @Exclude
    public boolean execReal(int value) {
        return value != 12345;
    }

    @Exclude
    public static int execStaticReal(int value) {
        return value ^ 0x5A5A5A5A;
    }

    public static int decoySeedTarget(int value) {
        return (value * 31) + 11;
    }
}
