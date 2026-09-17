package dev.skidfuscator.obfuscator.creator;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractExpressionTransformer;
import dev.skidfuscator.obfuscator.transform.impl.hash.StringEqualsHashTransformer;
import dev.skidfuscator.obfuscator.transform.impl.hash.StringEqualsIgnoreCaseHashTransformer;
import org.junit.jupiter.api.Test;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import org.mapleir.ir.code.stmt.ReturnStmt;
import org.mapleir.ir.locals.impl.StaticMethodLocalsPool;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import dev.skidfuscator.obfuscator.renamer.SkidRemapper;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class StringComparisonHashSemanticsTest {
    public static String evaluateString(String value) {
        evaluations++;
        return value;
    }
    @Test void objectArgumentVerifiesAndPreservesEqualsContract() throws Exception {
        Method emitted = comparison(false, true, "value");
        assertEquals(true, emitted.invoke(null, "value"));
        assertEquals(false, emitted.invoke(null, "other"));
        assertEquals(false, emitted.invoke(null, new Object[] {null}));
        assertEquals(false, emitted.invoke(null, new StringBuilder("value")));
        assertEquals(false, emitted.invoke(null, new Object() {
            @Override public String toString() { throw new AssertionError("must not coerce"); }
            @Override public boolean equals(Object other) { throw new AssertionError("must not reverse"); }
        }));
    }

    @Test void nullReceiverStillThrowsAndNullConstantArgumentReturnsFalse() throws Exception {
        for (boolean ignoreCase : new boolean[] {false, true}) {
            Method receiver = comparison(ignoreCase, false, "value");
            assertEquals(true, receiver.invoke(null, "value"));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> receiver.invoke(null, new Object[] {null}));
            assertInstanceOf(NullPointerException.class, failure.getCause());
            Method nullArgument = comparison(ignoreCase, false, null);
            assertEquals(false, nullArgument.invoke(null, "value"));
        }
    }

    @Test void ignoreCasePreservesUnicodeAndDoesNotDependOnDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            for (Locale locale : new Locale[] {Locale.ROOT, Locale.forLanguageTag("tr-TR")}) {
                Locale.setDefault(locale);
                for (String[] pair : new String[][] {{"I", "i"}, {"I", "\u0131"},
                        {"\u0130", "i"}, {"\u03a3", "\u03c2"}, {"\u00df", "SS"}, {"\ud801\udc00", "\ud801\udc28"},
                        {"\ud800", "\ud800"}, {"\udc00", "\ud800"}, {"\u0000", "\u0000"}}) {
                    for (boolean constantReceiver : new boolean[] {false, true}) {
                        Method emitted = comparison(true, constantReceiver, pair[0]);
                        assertEquals(pair[0].equalsIgnoreCase(pair[1]), emitted.invoke(null, pair[1]));
                    }
                }
                assertEquals(false, comparison(true, true, "I").invoke(null, new Object[] {null}));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void hashCollisionsRemainUnequal() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        for (boolean ignoreCase : new boolean[] {false, true}) {
            for (boolean receiver : new boolean[] {false, true}) {
                assertEquals(false, comparison(ignoreCase, receiver, "Aa").invoke(null, "BB"));
            }
        }
    }

    @Test void encodedLiteralRoundtripPreservesEveryUtf16CodeUnit() throws Exception {
        // Batches stay under CONSTANT_Utf8's limit even after encoding.
        for (int start = 0; start < 65536; start += 8192) {
            char[] chars = new char[8192];
            for (int i = 0; i < chars.length; i++) chars[i] = (char) (start + i);
            String literal = new String(chars);
            assertEquals(true, comparison(false, true, literal).invoke(null, literal));
        }
        assertEquals(true, comparison(false, false, "").invoke(null, ""));
    }

    @Test void sdkDisabledAndOversizeEncodingKeepOriginalInvocation() throws Exception {
        assertEquals(true, comparison(false, true, "value", false, false).invoke(null, "value"));
        assertEquals(true, comparison(true, true, "value", false, false).invoke(null, "VALUE"));
        // 65535 ASCII chars fit before encoding; the encoded stream cannot fit.
        String large = "a".repeat(65535);
        for (boolean ignoreCase : new boolean[] {false, true}) {
            assertEquals(true, comparison(ignoreCase, true, large, true, false).invoke(null, large));
        }
    }

    @Test void onlyExactlyOneStringLiteralIsEligible() throws Exception {
        for (boolean ignoreCase : new boolean[] {false, true}) {
            for (boolean bothConstants : new boolean[] {false, true}) {
                Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
                when(skid.getConfig().getBoolean("sdk.enabled", true)).thenReturn(true);
                AbstractExpressionTransformer transformer = ignoreCase
                        ? new StringEqualsIgnoreCaseHashTransformer(skid) : new StringEqualsHashTransformer(skid);
                Expr left = bothConstants ? new ConstantExpr("left")
                        : new VarExpr(new StaticMethodLocalsPool().get(0), Type.getType(String.class));
                Expr right = bothConstants ? new ConstantExpr("right")
                        : new VarExpr(new StaticMethodLocalsPool().get(1), Type.getType(String.class));
                InvocationExpr invocation = new VirtualInvocationExpr(InvocationExpr.CallType.VIRTUAL,
                        new Expr[] {left, right}, "java/lang/String", ignoreCase ? "equalsIgnoreCase" : "equals",
                        ignoreCase ? "(Ljava/lang/String;)Z" : "(Ljava/lang/Object;)Z");
                ReturnStmt result = new ReturnStmt(Type.BOOLEAN_TYPE, invocation);
                Method transform = transformer.getClass().getDeclaredMethod("transformExpression", Expr.class, ControlFlowGraph.class);
                transform.setAccessible(true);
                assertEquals(false, transform.invoke(transformer, invocation, null));
                assertSame(invocation, result.getExpression());
            }
        }
    }

    public static int evaluations;
    public static Object evaluate(Object value) {
        evaluations++;
        return value;
    }

    @Test void dynamicOperandIsEvaluatedExactlyOnce() throws Exception {
        for (boolean ignoreCase : new boolean[] {false, true}) {
            for (boolean receiver : new boolean[] {false, true}) {
                evaluations = 0;
                Method method = comparison(ignoreCase, receiver, "value", true, true, true);
                assertEquals(true, method.invoke(null, "value"));
                assertEquals(1, evaluations);
                evaluations = 0;
                if (receiver) assertEquals(false, method.invoke(null, new Object[] {null}));
                else assertThrows(InvocationTargetException.class, () -> method.invoke(null, new Object[] {null}));
                assertEquals(1, evaluations);
            }
        }
    }

    private static Method comparison(boolean ignoreCase, boolean constantReceiver, String literal) throws Exception {
        return comparison(ignoreCase, constantReceiver, literal, true, literal != null);
    }

    private static Method comparison(boolean ignoreCase, boolean constantReceiver, String literal,
                                     boolean sdkEnabled, boolean expectedTransform) throws Exception {
        return comparison(ignoreCase, constantReceiver, literal, sdkEnabled, expectedTransform, false);
    }

    private static Method comparison(boolean ignoreCase, boolean constantReceiver, String literal,
                                     boolean sdkEnabled, boolean expectedTransform, boolean sideEffect) throws Exception {
        Skidfuscator skid = mock(Skidfuscator.class, RETURNS_DEEP_STUBS);
        when(skid.getConfig().getBoolean("sdk.enabled", true)).thenReturn(sdkEnabled);
        AbstractExpressionTransformer transformer = ignoreCase
                ? new StringEqualsIgnoreCaseHashTransformer(skid) : new StringEqualsHashTransformer(skid);
        Class<?> parameter = !ignoreCase && constantReceiver ? Object.class : String.class;
        Expr variable = new VarExpr(new StaticMethodLocalsPool().get(0), Type.getType(parameter));
        if (sideEffect) {
            variable = new StaticInvocationExpr(new Expr[] {variable},
                    Type.getInternalName(StringComparisonHashSemanticsTest.class),
                    parameter == Object.class ? "evaluate" : "evaluateString",
                    "(" + Type.getDescriptor(parameter) + ")" + Type.getDescriptor(parameter));
        }
        Expr constant = new ConstantExpr(literal, Type.getType(String.class));
        InvocationExpr invocation = new VirtualInvocationExpr(InvocationExpr.CallType.VIRTUAL,
                constantReceiver ? new Expr[] {constant, variable} : new Expr[] {variable, constant},
                "java/lang/String", ignoreCase ? "equalsIgnoreCase" : "equals",
                ignoreCase ? "(Ljava/lang/String;)Z" : "(Ljava/lang/Object;)Z");
        ReturnStmt result = new ReturnStmt(Type.BOOLEAN_TYPE, invocation);
        Method transform = transformer.getClass().getDeclaredMethod("transformExpression", Expr.class, ControlFlowGraph.class);
        transform.setAccessible(true);
        assertEquals(expectedTransform, transform.invoke(transformer, invocation, null));
        if (expectedTransform) {
            assertNull(invocation.getParent(), "original comparison must be replaced");
            assertInstanceOf(StaticInvocationExpr.class, result.getExpression());
            InvocationExpr call = (InvocationExpr) result.getExpression();
            assertEquals("sdk/SDK", call.getOwner());
            assertEquals("compareEncoded", call.getName());
            assertEquals("(Ljava/lang/Object;Ljava/lang/String;II)Z", call.getDesc());
            Expr[] args = call.getArgumentExprs();
            String encoded = (String) ((ConstantExpr) args[1]).getConstant();
            int key = ((Number) ((ConstantExpr) args[2]).getConstant()).intValue();
            // Independent decode checks the transform-time wire format.
            char[] decoded = encoded.toCharArray();
            for (int i = 0; i < decoded.length; i++) {
                key = key * 1664525 + 1013904223;
                decoded[i] ^= (char) (key >>> 16);
            }
            assertEquals(literal, new String(decoded));
        } else {
            assertSame(result, invocation.getParent());
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/StringComparison", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "test", "(" + Type.getDescriptor(parameter) + ")Z", null, null);
        method.visitCode();
        result.toCode(method, null);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        // Exercise the same output ClassRemapper path as the production jar dumper,
        // including loading the real SDK under its renamed runtime owner.
        SkidRemapper remapper = new SkidRemapper("sdk/SDK", "fixture/EncodedRuntime");
        ClassWriter mapped = new ClassWriter(0);
        new ClassReader(writer.toByteArray()).accept(new ClassRemapper(mapped, remapper), 0);
        byte[] bytes = mapped.toByteArray();
        ClassWriter sdkWriter = new ClassWriter(0);
        try (java.io.InputStream input = sdk.SDK.class.getResourceAsStream("/sdk/SDK.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassRemapper(sdkWriter, remapper), 0);
        }
        byte[] runtimeBytes = sdkWriter.toByteArray();
        Class<?> type = new ClassLoader(StringComparisonHashSemanticsTest.class.getClassLoader()) {
            Class<?> define() {
                defineClass("fixture.EncodedRuntime", runtimeBytes, 0, runtimeBytes.length);
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        return type.getMethod("test", parameter);
    }
}
