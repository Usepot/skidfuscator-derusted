package dev.skidfuscator.testclasses.flow;

import dev.skidfuscator.testclasses.TestRun;

public class FlowFactoryMakerSample implements TestRun {
    private int value = 12;

    @Override
    public void run() {
        final int result = fold(4);

        if (result != 42) {
            throw new IllegalStateException("Unexpected result: " + result);
        }
    }

    private int fold(final int input) {
        int total = value;

        for (int i = 0; i < 5; i++) {
            total += input + i;
        }

        return total;
    }
}
