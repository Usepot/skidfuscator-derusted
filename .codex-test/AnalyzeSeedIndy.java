import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AnalyzeSeedIndy {
    private static final String SEED_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";
    private static final int UNKNOWN = Integer.MIN_VALUE;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: AnalyzeSeedIndy <jar>");

        Map<String, ClassNode> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, 0);
                    classes.put(node.name, node);
                }
            }
        }

        int shown = 0;
        for (ClassNode cn : classes.values()) {
            int[] decryptKeys = findDecryptKeys(cn);
            for (MethodNode mn : cn.methods) {
                List<AbstractInsnNode> insns = asList(mn.instructions);
                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode insn = insns.get(i);
                    if (!(insn instanceof InvokeDynamicInsnNode)) continue;
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                    Handle bsm = indy.bsm;
                    if (!bsm.getName().startsWith("skid$bootseed$") || !SEED_BOOTSTRAP_DESC.equals(bsm.getDesc())) continue;

                    Type[] argTypes = Type.getArgumentTypes(indy.desc);
                    SeedSource source = classifySeedSource(insns, i, mn, argTypes);
                    String decName = null;
                    String decOwner = null;
                    if (source.constantKnown && decryptKeys != null && indy.name.startsWith("skid$") && indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof String) {
                        decName = decrypt(indy.name, source.constantValue, decryptKeys);
                        decOwner = decrypt((String) indy.bsmArgs[1], source.constantValue, decryptKeys);
                    }

                    System.out.println("--- seed indy #" + (shown + 1) + " ---");
                    System.out.println("ownerMethod=" + cn.name + "." + mn.name + mn.desc);
                    System.out.println("indyName=" + indy.name);
                    System.out.println("indyDesc=" + indy.desc);
                    System.out.println("bsm=" + bsm.getOwner() + "." + bsm.getName() + bsm.getDesc());
                    System.out.println("bsmArgs=" + Arrays.toString(indy.bsmArgs));
                    System.out.println("seedSource=" + source.description);
                    if (source.constantKnown) {
                        System.out.println("recoveredSeed=" + source.constantValue);
                    }
                    if (decName != null) {
                        System.out.println("decryptedName=" + decName);
                        System.out.println("decryptedOwner=" + decOwner);
                    } else {
                        System.out.println("decryptedName=<not decrypted: seed not statically recoverable by simple scanner or keys missing>");
                    }
                    System.out.println("precedingInstructions:");
                    for (int j = Math.max(0, i - 12); j <= i; j++) {
                        System.out.println("  " + (j == i ? "> " : "  ") + insnToString(insns.get(j)));
                    }
                    shown++;
                    if (shown >= 12) return;
                }
            }
        }
    }

    private static SeedSource classifySeedSource(List<AbstractInsnNode> insns, int indyIndex, MethodNode method, Type[] indyArgs) {
        if (indyArgs.length == 0 || indyArgs[indyArgs.length - 1].getSort() != Type.INT) {
            return new SeedSource("no trailing int argument", false, 0);
        }

        // For the common seed-only callsites, the producer is immediately before indy.
        AbstractInsnNode p = previousReal(insns, indyIndex - 1);
        if (p == null) return new SeedSource("unknown: no previous instruction", false, 0);

        Integer c = intConst(p);
        if (c != null) return new SeedSource("literal constant pushed immediately before invokedynamic", true, c);

        if (p instanceof VarInsnNode && p.getOpcode() == Opcodes.ILOAD) {
            int var = ((VarInsnNode) p).var;
            return new SeedSource("dynamic local load ILOAD " + var + localMeaning(method, var), false, 0);
        }
        if (p.getOpcode() == Opcodes.IADD || p.getOpcode() == Opcodes.ISUB || p.getOpcode() == Opcodes.IMUL || p.getOpcode() == Opcodes.IDIV || p.getOpcode() == Opcodes.IREM || p.getOpcode() == Opcodes.IXOR || p.getOpcode() == Opcodes.IAND || p.getOpcode() == Opcodes.IOR) {
            ExprValue v = evalIntExpressionEndingAt(insns, previousRealIndex(insns, indyIndex - 1), 16);
            if (v.known) return new SeedSource("constant arithmetic expression ending in " + opcodeName(p.getOpcode()), true, v.value);
            return new SeedSource("computed int expression ending in " + opcodeName(p.getOpcode()) + " with dynamic inputs", false, 0);
        }
        if (p instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) p;
            return new SeedSource("dynamic method result " + m.owner + "." + m.name + m.desc, false, 0);
        }
        if (p instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) p;
            return new SeedSource("dynamic field load " + f.owner + "." + f.name + " " + f.desc, false, 0);
        }
        return new SeedSource("unknown producer: " + insnToString(p), false, 0);
    }

    private static String localMeaning(MethodNode method, int var) {
        int idx = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            if (var == 0) return " (this)";
            idx = 1;
        }
        Type[] args = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < args.length; i++) {
            int size = args[i].getSize();
            if (var >= idx && var < idx + size) return " (method parameter " + i + " of " + method.desc + ")";
            idx += size;
        }
        return " (local, not a method parameter)";
    }

    private static ExprValue evalIntExpressionEndingAt(List<AbstractInsnNode> insns, int endIndex, int window) {
        List<Integer> stack = new ArrayList<>();
        int start = Math.max(0, endIndex - window + 1);
        for (int i = start; i <= endIndex; i++) {
            AbstractInsnNode n = insns.get(i);
            if (n.getType() == AbstractInsnNode.LABEL || n.getType() == AbstractInsnNode.LINE || n.getType() == AbstractInsnNode.FRAME) continue;
            Integer c = intConst(n);
            if (c != null) {
                stack.add(c);
                continue;
            }
            int op = n.getOpcode();
            if (op == Opcodes.IADD || op == Opcodes.ISUB || op == Opcodes.IMUL || op == Opcodes.IDIV || op == Opcodes.IREM || op == Opcodes.IXOR || op == Opcodes.IAND || op == Opcodes.IOR) {
                if (stack.size() < 2) return ExprValue.unknown();
                int b = stack.remove(stack.size() - 1);
                int a = stack.remove(stack.size() - 1);
                try {
                    switch (op) {
                        case Opcodes.IADD: stack.add(a + b); break;
                        case Opcodes.ISUB: stack.add(a - b); break;
                        case Opcodes.IMUL: stack.add(a * b); break;
                        case Opcodes.IDIV: stack.add(a / b); break;
                        case Opcodes.IREM: stack.add(a % b); break;
                        case Opcodes.IXOR: stack.add(a ^ b); break;
                        case Opcodes.IAND: stack.add(a & b); break;
                        case Opcodes.IOR: stack.add(a | b); break;
                    }
                } catch (ArithmeticException ex) {
                    return ExprValue.unknown();
                }
                continue;
            }
            if (op == Opcodes.INEG) {
                if (stack.isEmpty()) return ExprValue.unknown();
                stack.set(stack.size() - 1, -stack.get(stack.size() - 1));
                continue;
            }
            // Ignore stack-neutral-ish labels only; anything else makes it dynamic/unknown.
            if (op >= 0) return ExprValue.unknown();
        }
        return stack.isEmpty() ? ExprValue.unknown() : ExprValue.known(stack.get(stack.size() - 1));
    }

    private static int previousRealIndex(List<AbstractInsnNode> insns, int start) {
        for (int i = start; i >= 0; i--) {
            int t = insns.get(i).getType();
            if (t != AbstractInsnNode.LABEL && t != AbstractInsnNode.LINE && t != AbstractInsnNode.FRAME) return i;
        }
        return -1;
    }

    private static AbstractInsnNode previousReal(List<AbstractInsnNode> insns, int start) {
        int idx = previousRealIndex(insns, start);
        return idx < 0 ? null : insns.get(idx);
    }

    private static int[] findDecryptKeys(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (!mn.name.startsWith("skid$decrypt$") || !DECRYPT_DESC.equals(mn.desc)) continue;
            List<AbstractInsnNode> insns = asList(mn.instructions);
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode n = insns.get(i);
                if (n.getOpcode() == Opcodes.NEWARRAY && n instanceof IntInsnNode && ((IntInsnNode) n).operand == Opcodes.T_BYTE) {
                    Integer len = intConst(previousReal(insns, i - 1));
                    if (len == null || len <= 0 || len > 512) continue;
                    int[] keys = new int[len];
                    int filled = 0;
                    for (int j = i + 1; j < insns.size() - 2; j++) {
                        Integer idx = intConst(insns.get(j));
                        Integer val = intConst(insns.get(j + 1));
                        if (idx != null && val != null && insns.get(j + 2).getOpcode() == Opcodes.BASTORE && idx >= 0 && idx < len) {
                            keys[idx] = val & 0xFF;
                            filled++;
                            j += 2;
                            if (filled >= len) return keys;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String decrypt(String encoded, int key, int[] keys) {
        String hex = encoded.substring("skid$".length());
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= keyBytes[i % keyBytes.length];
            bytes[i] ^= (byte) keys[i % keys.length];
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<AbstractInsnNode> asList(InsnList list) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = list.getFirst(); n != null; n = n.getNext()) out.add(n);
        return out;
    }

    private static Integer intConst(AbstractInsnNode n) {
        if (n == null) return null;
        switch (n.getOpcode()) {
            case Opcodes.ICONST_M1: return -1;
            case Opcodes.ICONST_0: return 0;
            case Opcodes.ICONST_1: return 1;
            case Opcodes.ICONST_2: return 2;
            case Opcodes.ICONST_3: return 3;
            case Opcodes.ICONST_4: return 4;
            case Opcodes.ICONST_5: return 5;
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                return ((IntInsnNode) n).operand;
            case Opcodes.LDC:
                Object cst = ((LdcInsnNode) n).cst;
                return cst instanceof Integer ? (Integer) cst : null;
            default:
                return null;
        }
    }

    private static String insnToString(AbstractInsnNode n) {
        if (n == null) return "<null>";
        if (n instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode i = (InvokeDynamicInsnNode) n;
            return "INVOKEDYNAMIC " + i.name + i.desc + " bsm=" + i.bsm.getOwner() + "." + i.bsm.getName() + i.bsm.getDesc() + " args=" + Arrays.toString(i.bsmArgs);
        }
        if (n instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) n;
            return opcodeName(n.getOpcode()) + " " + m.owner + "." + m.name + m.desc;
        }
        if (n instanceof VarInsnNode) return opcodeName(n.getOpcode()) + " " + ((VarInsnNode) n).var;
        if (n instanceof IntInsnNode) return opcodeName(n.getOpcode()) + " " + ((IntInsnNode) n).operand;
        if (n instanceof LdcInsnNode) return "LDC " + ((LdcInsnNode) n).cst;
        if (n instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) n;
            return opcodeName(n.getOpcode()) + " " + f.owner + "." + f.name + " " + f.desc;
        }
        if (n instanceof TypeInsnNode) return opcodeName(n.getOpcode()) + " " + ((TypeInsnNode) n).desc;
        if (n instanceof JumpInsnNode) return opcodeName(n.getOpcode());
        if (n instanceof LabelNode) return "LABEL";
        if (n instanceof LineNumberNode) return "LINE " + ((LineNumberNode) n).line;
        if (n instanceof FrameNode) return "FRAME";
        return opcodeName(n.getOpcode());
    }

    private static String opcodeName(int opcode) {
        if (opcode < 0) return "NOOP";
        String[] names = org.objectweb.asm.util.Printer.OPCODES;
        return opcode < names.length ? names[opcode] : ("OP" + opcode);
    }

    private static final class SeedSource {
        final String description;
        final boolean constantKnown;
        final int constantValue;
        SeedSource(String description, boolean constantKnown, int constantValue) {
            this.description = description;
            this.constantKnown = constantKnown;
            this.constantValue = constantValue;
        }
    }

    private static final class ExprValue {
        final boolean known;
        final int value;
        private ExprValue(boolean known, int value) { this.known = known; this.value = value; }
        static ExprValue known(int value) { return new ExprValue(true, value); }
        static ExprValue unknown() { return new ExprValue(false, 0); }
    }
}
