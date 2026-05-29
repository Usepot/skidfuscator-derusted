package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

/**
 * Exercises {@code MethodMergeTransformer}'s intra-group call rewriting: calls
 * between merged members (and a member's call to itself) must be rerouted to the
 * host before the bodies are relocated, otherwise recursion would dangle.
 * {fib,fact} self-recurse into the int host; {isEven,isOdd} mutually recurse
 * into the boolean host.
 */
public class MergeRecursiveClazz implements TestRun {

    @Override
    public void run() {
        check(fib(10) == 55, "fib");
        check(fact(5) == 120, "fact");
        check(isEven(8), "isEven-even");
        check(!isEven(7), "isEven-odd");
        check(isOdd(7), "isOdd-odd");
        check(!isOdd(8), "isOdd-even");
    }

    private static int fib(int n) {
        if (n < 2) {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }

    private static int fact(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * fact(n - 1);
    }

    private static boolean isEven(int n) {
        if (n == 0) {
            return true;
        }
        return isOdd(n - 1);
    }

    private static boolean isOdd(int n) {
        if (n == 0) {
            return false;
        }
        return isEven(n - 1);
    }

    private static void check(boolean cond, String label) {
        if (!cond) {
            throw new IllegalStateException("Merge recursive failed: " + label);
        }
    }
}
