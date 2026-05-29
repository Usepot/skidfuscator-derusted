package dev.skidfuscator.testclasses.claude;

import dev.skidfuscator.testclasses.TestRun;

/**
 * Exercises {@code MethodDispatchTransformer} across every call opcode it
 * funnels: INVOKESTATIC, INVOKEVIRTUAL (incl. polymorphic dispatch through a
 * supertype reference), INVOKEINTERFACE, and INVOKESPECIAL (both a private call
 * and a {@code super.} call). The INVOKESPECIAL cases specifically validate the
 * receiver-cast logic in the dispatcher, which must keep the bytecode verifiable
 * even though the synthetic dispatcher is static.
 */
public class DispatchCallKindsClazz implements TestRun {

    public interface Greeter {
        int greet(int base);
    }

    public static class Base {
        int describe(int x) {
            return x + 1;
        }
    }

    public static class Derived extends Base implements Greeter {
        @Override
        int describe(int x) {
            return x + 100;
        }

        @Override
        public int greet(int base) {
            return base + 7;
        }

        int callSuper(int x) {
            return super.describe(x); // INVOKESPECIAL Base.describe
        }

        int callPrivate(int x) {
            return priv(x); // INVOKESPECIAL Derived.priv
        }

        private int priv(int x) {
            return x - 3;
        }
    }

    @Override
    public void run() {
        // INVOKESTATIC
        check(staticAdd(2, 3) == 5, "static");

        final Derived d = new Derived();

        // INVOKEVIRTUAL (overridden body)
        check(d.describe(1) == 101, "virtual");

        // INVOKEVIRTUAL through a Base reference -> dynamic dispatch to Derived
        final Base b = d;
        check(b.describe(1) == 101, "virtual-poly");

        // INVOKEINTERFACE
        final Greeter g = d;
        check(g.greet(10) == 17, "interface");

        // INVOKESPECIAL super call
        check(d.callSuper(1) == 2, "super");

        // INVOKESPECIAL private call
        check(d.callPrivate(10) == 7, "private");
    }

    static int staticAdd(int a, int b) {
        return a + b;
    }

    private static void check(boolean cond, String label) {
        if (!cond) {
            throw new IllegalStateException("Call-kind dispatch failed: " + label);
        }
    }
}
