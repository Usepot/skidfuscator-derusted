package dev.skidfuscator.obfuscator.transform.impl.hash;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractExpressionTransformer;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.objectweb.asm.Type;

/** Compatibility name: protects literals with reversible encoding, never hash equality. */
public class StringEqualsHashTransformer extends AbstractExpressionTransformer {
    public StringEqualsHashTransformer(Skidfuscator skidfuscator) {
        super(skidfuscator, "String Equals Hash");
        requiresSdk();
    }

    @Override
    protected boolean matchesExpression(Expr expr) {
        if (!(expr instanceof InvocationExpr invocationExpr)) {
            return false;
        }

        return invocationExpr.getOwner().equals("java/lang/String")
                && invocationExpr.getName().equals("equals")
                && invocationExpr.getDesc().equals("(Ljava/lang/Object;)Z");
    }

    @Override
    protected boolean transformExpression(Expr expr, ControlFlowGraph cfg) {
        return matchesExpression(expr) && replaceComparison(skidfuscator, expr, false);
    }

    static boolean replaceComparison(Skidfuscator skidfuscator, Expr expr, boolean ignoreCase) {
        if (!skidfuscator.getConfig().getBoolean("sdk.enabled", true)
                || expr.getParent() == null) {
            return false;
        }
        InvocationExpr invocation = (InvocationExpr) expr;
        Expr[] operands = invocation.getArgumentExprs();
        if (invocation.getCallType() != InvocationExpr.CallType.VIRTUAL || operands.length != 2) {
            return false;
        }
        boolean receiverLiteral = isStringLiteral(operands[0]);
        if (receiverLiteral == isStringLiteral(operands[1])) {
            return false; // Exactly one original operand must be a String literal.
        }
        String literal = (String) ((ConstantExpr) operands[receiverLiteral ? 0 : 1]).getConstant();
        int key = RandomUtil.nextInt();
        char[] encoded = literal.toCharArray();
        int state = key;
        int utfBytes = 0;
        for (int i = 0; i < encoded.length; i++) {
            state = state * 1664525 + 1013904223;
            encoded[i] ^= (char) (state >>> 16);
            char c = encoded[i];
            // CONSTANT_Utf8 uses modified UTF-8, including NUL and lone surrogates.
            utfBytes += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
            if (utfBytes > 65535) {
                return false;
            }
        }
        // Copy the dynamic subtree once; replacing the old call removes its original.
        // The other operand is an LDC, so it has no user-visible evaluation effects.
        StaticInvocationExpr replacement = new StaticInvocationExpr(new Expr[] {
                operands[receiverLiteral ? 1 : 0].copy(),
                new ConstantExpr(new String(encoded), Type.getType(String.class)),
                new ConstantExpr(key, Type.INT_TYPE),
                new ConstantExpr((receiverLiteral ? 1 : 0) | (ignoreCase ? 2 : 0), Type.INT_TYPE)
        }, "sdk/SDK", "compareEncoded", "(Ljava/lang/Object;Ljava/lang/String;II)Z");
        // Keep the original SDK owner: the output ClassRemapper maps both this
        // symbolic call and the SDK class imported by SdkInjectorTransformer.
        expr.getParent().overwrite(expr, replacement);
        return true;
    }

    private static boolean isStringLiteral(Expr expr) {
        return expr instanceof ConstantExpr && ((ConstantExpr) expr).getConstant() instanceof String;
    }
}
