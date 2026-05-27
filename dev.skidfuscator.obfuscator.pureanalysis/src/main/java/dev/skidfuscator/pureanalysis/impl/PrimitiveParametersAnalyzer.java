package dev.skidfuscator.pureanalysis.impl;

import dev.skidfuscator.pureanalysis.PurityAnalyzer;
import dev.skidfuscator.pureanalysis.Analyzer;
import dev.skidfuscator.pureanalysis.PurityContext;
import dev.skidfuscator.pureanalysis.PurityReport;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PrimitiveParametersAnalyzer extends Analyzer {
    private static final Set<String> PRIMITIVE_DESCRIPTORS = new HashSet<>(Arrays.asList(
            "B", // byte
            "C", // char
            "D", // double
            "F", // float
            "I", // int
            "J", // long
            "S", // short
            "Z",  // boolean
            "Ljava/lang/String;"
    ));


    public PrimitiveParametersAnalyzer(PurityContext context, PurityAnalyzer analyzer) {
        super("Primitive Parameters", context, analyzer);
    }

    @Override
    public PurityReport analyze(Context ctx) {
        final MethodNode methodNode = ctx.method();
        Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);

        for (Type type : argumentTypes) {
            if (!isAcceptable(type)) {
                return impure(String.format(
                        "Argument %s is not a primitive type and not a pure class", type.getDescriptor()
                ));
            }
        }

        // Also check return type
        Type returnType = Type.getReturnType(methodNode.desc);
        if (!returnType.getDescriptor().equals("V") && !isAcceptable(returnType)) {
            return impure(
                    String.format("Return type %s is not a primitive type and not a pure class", returnType.getDescriptor())
            );
        }

        return pure();
    }

    /**
     * A type is acceptable for a pure method when it is either:
     *   - a primitive (or {@link String}),
     *   - an array of acceptable element types, or
     *   - a reference type that has not been marked impure in the context.
     *
     * The reference-type leg matches the original return-type semantics so the
     * analyzer doesn't reject methods that legitimately take immutable
     * domain objects (Matrix, ImmutablePoint, Comparable, ...).
     */
    private boolean isAcceptable(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return isAcceptable(type.getElementType());
        }
        if (isPrimitiveType(type)) {
            return true;
        }
        return context.isPure(type.getInternalName());
    }

    private boolean isPrimitiveType(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return isPrimitiveType(type.getElementType());
        }
        return PRIMITIVE_DESCRIPTORS.contains(type.getDescriptor());
    }
}