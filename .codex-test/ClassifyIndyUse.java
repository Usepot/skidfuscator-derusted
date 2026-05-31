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

public class ClassifyIndyUse {
    private static final String SEED_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String STATIC_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ClassifyIndyUse <jar>");

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

        Map<String, int[]> callerCounts = new TreeMap<>();
        Map<String, int[]> ownerCounts = new TreeMap<>();
        Map<String, int[]> opcodeCounts = new TreeMap<>();
        List<Row> seedRows = new ArrayList<>();
        List<Row> staticRows = new ArrayList<>();

        Map<String, List<Candidate>> candidatesByDesc = new HashMap<>();
        for (ClassNode cn : classes.values()) {
            for (MethodNode mn : cn.methods) {
                if (mn.name.startsWith("skid$")) continue;
                candidatesByDesc.computeIfAbsent(mn.desc, k -> new ArrayList<>())
                        .add(new Candidate(cn.name, cn.name.replace('/', '.'), mn.name, mn.desc, (mn.access & Opcodes.ACC_STATIC) != 0));
            }
        }

        for (ClassNode caller : classes.values()) {
            int[] keys = findDecryptKeys(caller);
            for (MethodNode mn : caller.methods) {
                for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (!(n instanceof InvokeDynamicInsnNode)) continue;
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) n;
                    Handle bsm = indy.bsm;
                    boolean seeded = bsm.getName().startsWith("skid$bootseed$") && SEED_BOOTSTRAP_DESC.equals(bsm.getDesc());
                    boolean stat = bsm.getName().startsWith("skid$bootstrap$") && STATIC_BOOTSTRAP_DESC.equals(bsm.getDesc());
                    if (!seeded && !stat) continue;

                    int opcode = indy.bsmArgs.length > 0 && indy.bsmArgs[0] instanceof Integer ? (Integer) indy.bsmArgs[0] : -1;
                    String encOwner = indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof String ? (String) indy.bsmArgs[1] : "";
                    Type targetType = indy.bsmArgs.length > 2 && indy.bsmArgs[2] instanceof Type ? (Type) indy.bsmArgs[2] : null;
                    String targetDesc = targetType == null ? "?" : targetType.getDescriptor();
                    Integer key = null;
                    String decName = "?";
                    String decOwner = "?";

                    if (keys != null && stat && indy.bsmArgs.length > 3 && indy.bsmArgs[3] instanceof Integer) {
                        key = (Integer) indy.bsmArgs[3];
                        decName = safeDecrypt(indy.name, key, keys);
                        decOwner = safeDecrypt(encOwner, key, keys);
                    } else if (keys != null && seeded) {
                        key = recoverSeed(keys, candidatesByDesc.getOrDefault(targetDesc, Collections.emptyList()), opcode, indy.name, encOwner);
                        if (key != null) {
                            decName = safeDecrypt(indy.name, key, keys);
                            decOwner = safeDecrypt(encOwner, key, keys);
                        }
                    }

                    Row row = new Row(caller.name, mn.name + mn.desc, opcodeName(opcode), decOwner, decName, targetDesc, key, seedSource(mn, n));
                    if (seeded) seedRows.add(row); else staticRows.add(row);

                    callerCounts.computeIfAbsent(caller.name, k -> new int[2])[seeded ? 0 : 1]++;
                    ownerCounts.computeIfAbsent(decOwner, k -> new int[2])[seeded ? 0 : 1]++;
                    opcodeCounts.computeIfAbsent(opcodeName(opcode), k -> new int[2])[seeded ? 0 : 1]++;
                }
            }
        }

        System.out.println("TOTAL seed=" + seedRows.size() + " static=" + staticRows.size());
        System.out.println();
        printCounts("BY_CALLER_CLASS", callerCounts, 80);
        System.out.println();
        printCounts("BY_DECRYPTED_TARGET_OWNER", ownerCounts, 80);
        System.out.println();
        printCounts("BY_OPCODE", opcodeCounts, 20);
        System.out.println();
        printRows("SEED_SAMPLES", seedRows, 80);
        System.out.println();
        printRows("STATIC_SAMPLES", staticRows, 50);
    }

    private static String seedSource(MethodNode mn, AbstractInsnNode indyInsn) {
        AbstractInsnNode p = prevReal(indyInsn.getPrevious());
        if (p == null) return "?";
        Integer c = intConst(p);
        if (c != null) return "literal " + c;
        if (p instanceof VarInsnNode) return opcodeName(p.getOpcode()) + " " + ((VarInsnNode)p).var;
        if (p instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode)p;
            return opcodeName(p.getOpcode()) + " " + f.owner + "." + f.name;
        }
        if (p instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode)p;
            return opcodeName(p.getOpcode()) + " " + m.owner + "." + m.name + m.desc;
        }
        return opcodeName(p.getOpcode());
    }

    private static void printCounts(String title, Map<String, int[]> counts, int max) {
        System.out.println(title);
        counts.entrySet().stream()
                .sorted((a,b) -> Integer.compare((b.getValue()[0]+b.getValue()[1]), (a.getValue()[0]+a.getValue()[1])))
                .limit(max)
                .forEach(e -> System.out.printf("seed=%3d static=%3d total=%3d %s%n", e.getValue()[0], e.getValue()[1], e.getValue()[0]+e.getValue()[1], e.getKey()));
    }

    private static void printRows(String title, List<Row> rows, int max) {
        System.out.println(title);
        for (int i = 0; i < Math.min(max, rows.size()); i++) {
            Row r = rows.get(i);
            System.out.printf("%03d caller=%s.%s op=%s target=%s.%s%s key=%s seedProducer=%s%n",
                    i+1, r.caller, r.callerMethod, r.opcode, r.owner, r.name, r.desc, r.key == null ? "?" : r.key.toString(), r.seedProducer);
        }
    }

    private static Integer recoverSeed(int[] keys, List<Candidate> candidates, int opcode, String encName, String encOwner) {
        for (Candidate c : candidates) {
            if (opcode == Opcodes.INVOKESTATIC && !c.isStatic) continue;
            if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESPECIAL) && c.isStatic) continue;
            Integer seed = recoverKeyFromPairs(keys, new Pair(encName, c.name), new Pair(encOwner, c.binaryOwner));
            if (seed == null) continue;
            if (c.name.equals(safeDecrypt(encName, seed, keys)) && c.binaryOwner.equals(safeDecrypt(encOwner, seed, keys))) return seed;
        }
        return null;
    }

    private static Integer recoverKeyFromPairs(int[] keys, Pair... pairs) {
        for (int keyLen = 1; keyLen <= 11; keyLen++) {
            byte[] keyBytes = new byte[keyLen];
            boolean[] set = new boolean[keyLen];
            boolean ok = true;
            for (Pair pair : pairs) {
                if (pair.enc == null || pair.plain == null || !pair.enc.startsWith("skid$")) { ok = false; break; }
                byte[] enc = hexBytes(pair.enc.substring("skid$".length()));
                byte[] plain = pair.plain.getBytes(StandardCharsets.UTF_8);
                if (enc.length != plain.length) { ok = false; break; }
                for (int i = 0; i < enc.length; i++) {
                    byte b = (byte)(enc[i] ^ plain[i] ^ (byte) keys[i % keys.length]);
                    int pos = i % keyLen;
                    if (set[pos] && keyBytes[pos] != b) { ok = false; break; }
                    keyBytes[pos] = b;
                    set[pos] = true;
                }
                if (!ok) break;
            }
            if (!ok) continue;
            for (int i = 0; i < keyLen; i++) {
                if (!set[i]) { ok = false; break; }
                char ch = (char)(keyBytes[i] & 0xFF);
                if (i == 0 && ch == '-') continue;
                if (ch < '0' || ch > '9') { ok = false; break; }
            }
            if (!ok) continue;
            String s = new String(keyBytes, StandardCharsets.UTF_8);
            if ("-".equals(s) || s.startsWith("--") || (s.length() > 1 && s.charAt(0) == '0') || (s.startsWith("-") && s.length() > 2 && s.charAt(1) == '0')) continue;
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static int[] findDecryptKeys(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (!mn.name.startsWith("skid$decrypt$") || !DECRYPT_DESC.equals(mn.desc)) continue;
            List<AbstractInsnNode> insns = new ArrayList<>();
            for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) insns.add(n);
            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode n = insns.get(i);
                if (n.getOpcode() == Opcodes.NEWARRAY && n instanceof IntInsnNode && ((IntInsnNode) n).operand == Opcodes.T_BYTE) {
                    Integer len = intConst(prevRealAt(insns, i - 1));
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

    private static AbstractInsnNode prevRealAt(List<AbstractInsnNode> insns, int start) {
        for (int i = start; i >= 0; i--) {
            AbstractInsnNode n = insns.get(i);
            int t = n.getType();
            if (t != AbstractInsnNode.LABEL && t != AbstractInsnNode.LINE && t != AbstractInsnNode.FRAME) return n;
        }
        return null;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode n) {
        while (n != null) {
            int t = n.getType();
            if (t != AbstractInsnNode.LABEL && t != AbstractInsnNode.LINE && t != AbstractInsnNode.FRAME) return n;
            n = n.getPrevious();
        }
        return null;
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
            case Opcodes.SIPUSH: return ((IntInsnNode)n).operand;
            case Opcodes.LDC:
                Object cst = ((LdcInsnNode)n).cst;
                return cst instanceof Integer ? (Integer)cst : null;
            default: return null;
        }
    }

    private static String safeDecrypt(String encoded, int key, int[] keys) {
        try { return decrypt(encoded, key, keys); } catch (Throwable t) { return "?"; }
    }

    private static String decrypt(String encoded, int key, int[] keys) {
        byte[] bytes = hexBytes(encoded.substring("skid$".length()));
        byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= keyBytes[i % keyBytes.length];
            bytes[i] ^= (byte)keys[i % keys.length];
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] hexBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return bytes;
    }

    private static String opcodeName(int opcode) {
        if (opcode == -1) return "?";
        String[] names = org.objectweb.asm.util.Printer.OPCODES;
        return opcode >= 0 && opcode < names.length ? names[opcode] : String.valueOf(opcode);
    }

    private static final class Row {
        final String caller, callerMethod, opcode, owner, name, desc, seedProducer;
        final Integer key;
        Row(String caller, String callerMethod, String opcode, String owner, String name, String desc, Integer key, String seedProducer) {
            this.caller = caller; this.callerMethod = callerMethod; this.opcode = opcode; this.owner = owner; this.name = name; this.desc = desc; this.key = key; this.seedProducer = seedProducer;
        }
    }
    private static final class Candidate {
        final String internalOwner, binaryOwner, name, desc; final boolean isStatic;
        Candidate(String internalOwner, String binaryOwner, String name, String desc, boolean isStatic) { this.internalOwner=internalOwner; this.binaryOwner=binaryOwner; this.name=name; this.desc=desc; this.isStatic=isStatic; }
    }
    private static final class Pair { final String enc, plain; Pair(String enc, String plain) { this.enc=enc; this.plain=plain; } }
}
