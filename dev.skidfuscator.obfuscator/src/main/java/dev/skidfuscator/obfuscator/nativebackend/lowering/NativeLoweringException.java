package dev.skidfuscator.obfuscator.nativebackend.lowering;

import java.util.Objects;

/** Raised before mutation when a selected Java method is outside the supported native lowering subset. */
public final class NativeLoweringException extends IllegalArgumentException {
    private final Reason reason;
    private final String method;
    private final String nodeType;

    public NativeLoweringException(final String message) {
        this(Reason.UNSUPPORTED_METHOD, "", "", message, null);
    }

    public NativeLoweringException(
            final Reason reason,
            final String method,
            final String nodeType,
            final String message
    ) {
        this(reason, method, nodeType, message, null);
    }

    public NativeLoweringException(
            final Reason reason,
            final String method,
            final String nodeType,
            final String message,
            final Throwable cause
    ) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.method = Objects.requireNonNull(method, "method");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType");
    }

    public Reason reason() {
        return reason;
    }

    public String method() {
        return method;
    }

    public String nodeType() {
        return nodeType;
    }

    public enum Reason {
        INVALID_METHOD,
        INVALID_CONTROL_FLOW,
        NON_SSA_LOCAL,
        UNSUPPORTED_METHOD,
        UNSUPPORTED_STATEMENT,
        UNSUPPORTED_EXPRESSION,
        UNSUPPORTED_CONSTANT,
        UNSUPPORTED_TYPE,
        TYPE_MISMATCH,
        VERIFICATION_FAILED
    }
}
