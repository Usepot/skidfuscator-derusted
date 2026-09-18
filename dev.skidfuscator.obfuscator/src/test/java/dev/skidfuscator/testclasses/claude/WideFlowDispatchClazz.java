package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;
import java.util.ArrayList;
import java.util.List;

/** Mirrors OrbitServerSelectionList: clear, immediate edge, loop index zero, virtual indexed read. */
public class WideFlowDispatchClazz implements TestRun {
    private final List<Integer> output = new ArrayList<>();

    private void repopulate(List<Integer> input) {
        output.clear();
        for (int i = 0; i < input.size(); i++) {
            output.add(input.get(i));
        }
    }

    @Override
    public void run() {
        List<Integer> one = new ArrayList<>();
        one.add(73);
        repopulate(one);
        if (output.size() != 1 || output.get(0) != 73) {
            throw new IllegalStateException("wide flow/dispatch corrupted loop semantics: " + output);
        }
    }
}
