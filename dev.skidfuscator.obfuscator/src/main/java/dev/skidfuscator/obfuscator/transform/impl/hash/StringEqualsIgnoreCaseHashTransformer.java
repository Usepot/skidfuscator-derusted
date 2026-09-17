package dev.skidfuscator.obfuscator.transform.impl.hash;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.transform.AbstractExpressionTransformer;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;

/** Compatibility name: encoded literals with target-JDK equalsIgnoreCase semantics. */
public class StringEqualsIgnoreCaseHashTransformer extends AbstractExpressionTransformer {
    public StringEqualsIgnoreCaseHashTransformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "String Eq Ig Case Hash");
        requiresSdk();
    }

    @Override
    protected boolean matchesExpression(Expr expr) {
        if (!(expr instanceof InvocationExpr invocationExpr)) {
            return false;
        }

        return invocationExpr.getOwner().equals("java/lang/String")
                && invocationExpr.getName().equals("equalsIgnoreCase")
                && invocationExpr.getDesc().equals("(Ljava/lang/String;)Z");
    }

    @Override
    protected boolean transformExpression(Expr expr, ControlFlowGraph cfg) {
        return matchesExpression(expr)
                && StringEqualsHashTransformer.replaceComparison(skidfuscator, expr, true);
    }
}
