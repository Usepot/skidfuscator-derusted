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

public class RecoverSeedString {
    private static final String SEED_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    private static final String STATIC_BOOTSTRAP_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";
    private static final String DECRYPT_DESC = "(Ljava/lang/String;I)Ljava/lang/String;";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: RecoverSeedString <jar>");

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

        List<Candidate> candidates = new ArrayList<>();
        for (ClassNode cn : classes.values()) {
            for (MethodNode mn : cn.methods) {
                if (mn.name.startsWith("skid$")) continue;
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
                candidates.add(new Candidate(cn.name, cn.name.replace('/', '.'), mn.name, mn.desc, isStatic));
            }
        }

        int recoveredSeed = 0;
        int attemptedSeed = 0;
        int recoveredStatic = 0;

        for (ClassNode caller : classes.values()) {
            int[] keys = findDecryptKeys(caller);
            if (keys == null) continue;

            for (MethodNode mn : caller.methods) {
                for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (!(n instanceof InvokeDynamicInsnNode)) continue;
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) n;
                    Handle bsm = indy.bsm;

                    if (bsm.getName().startsWith("skid$bootstrap$") && STATIC_BOOTSTRAP_DESC.equals(bsm.getDesc())
                            && indy.bsmArgs.length == 4 && indy.bsmArgs[1] instanceof String && indy.bsmArgs[2] instanceof Type && indy.bsmArgs[3] instanceof Integer) {
                        int key = (Integer) indy.bsmArgs[3];
                        String decName = decrypt(indy.name, key, keys);
                        String decOwner = decrypt((String) indy.bsmArgs[1], key, keys);
                        System.out.println("--- static-key decrypted sample ---");
                        System.out.println("caller=" + caller.name + "." + mn.name + mn.desc);
                        System.out.println("keyConstant=" + key);
                        System.out.println("decryptedName=" + decName);
                        System.out.println("decryptedOwner=" + decOwner);
                        System.out.println("targetType=" + indy.bsmArgs[2]);
                        recoveredStatic++;
                        if (recoveredStatic >= 1 && recoveredSeed >= 1) return;
                    }

                    if (!bsm.getName().startsWith("skid$bootseed$") || !SEED_BOOTSTRAP_DESC.equals(bsm.getDesc())) continue;
                    if (indy.bsmArgs.length != 3 || !(indy.bsmArgs[1] instanceof String) || !(indy.bsmArgs[2] instanceof Type)) continue;
                    attemptedSeed++;

                    int opcode = (Integer) indy.bsmArgs[0];
                    String encOwner = (String) indy.bsmArgs[1];
                    String realDesc = ((Type) indy.bsmArgs[2]).getDescriptor();

                    for (Candidate c : candidates) {
                        if (!c.desc.equals(realDesc)) continue;
                        if (opcode == Opcodes.INVOKESTATIC && !c.isStatic) continue;
                        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESPECIAL) && c.isStatic) continue;

                        Integer seed = recoverKeyFromPairs(keys,
                                new Pair(indy.name, c.name),
                                new Pair(encOwner, c.binaryOwner));
                        if (seed == null) continue;

                        String decName = decrypt(indy.name, seed, keys);
                        String decOwner = decrypt(encOwner, seed, keys);
                        if (!decName.equals(c.name) || !decOwner.equals(c.binaryOwner)) continue;

                        System.out.println("--- seed-key recovered/decrypted sample ---");
                        System.out.println("caller=" + caller.name + "." + mn.name + mn.desc);
                        System.out.println("indyName=" + indy.name);
                        System.out.println("encryptedOwner=" + encOwner);
                        System.out.println("recoveredSeedValue=" + seed);
                        System.out.println("decryptedName=" + decName);
                        System.out.println("decryptedOwner=" + decOwner);
                        System.out.println("targetType=" + realDesc);
                        System.out.println("opcode=" + opcodeName(opcode));
                        System.out.println("note=seed value was recovered by known-candidate matching; the callsite itself passes it as runtime trailing int, not as a bootstrap constant");
                        recoveredSeed++;
                        if (recoveredStatic >= 1 && recoveredSeed >= 1) return;
                        break;
                    }
                }
            }
        }

        System.out.println("attemptedSeed=" + attemptedSeed);
        System.out.println("recoveredSeed=" + recoveredSeed);
        System.out.println("recoveredStatic=" + recoveredStatic);
    }

    private static Integer recoverKeyFromPairs(int[] keys, Pair... pairs) {
        for (int keyLen = 1; keyLen <= 11; keyLen++) {
            byte[] keyBytes = new byte[keyLen];
            boolean[] set = new boolean[keyLen];
            boolean ok = true;

            for (Pair pair : pairs) {
                if (pair.plain == null || pair.enc == null || !pair.enc.startsWith("skid$")) { ok = false; break; }
                byte[] enc = hexBytes(pair.enc.substring("skid$".length()));
                byte[] plain = pair.plain.getBytes(StandardCharsets.UTF_8);
                if (enc.length != plain.length) { ok = false; break; }
                for (int i = 0; i < enc.length; i++) {
                    byte b = (byte) (enc[i] ^ plain[i] ^ (byte) keys[i % keys.length]);
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
                char ch = (char) (keyBytes[i] & 0xFF);
                if (i == 0 && ch == '-') continue;
                if (ch < '0' || ch > '9') { ok = false; break; }
            }
            if (!ok) continue;

            String s = new String(keyBytes, StandardCharsets.UTF_8);
            if ("-".equals(s) || s.startsWith("--") || (s.length() > 1 && s.charAt(0) == '0') || (s.startsWith("-") && s.length() > 2 && s.charAt(1) == '0')) continue;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
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
        byte[] bytes = hexBytes(encoded.substring("skid$".length()));
        byte[] keyBytes = Integer.toString(key).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= keyBytes[i % keyBytes.length];
            bytes[i] ^= (byte) keys[i % keys.length];
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] hexBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static List<AbstractInsnNode> asList(InsnList list) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode n = list.getFirst(); n != null; n = n.getNext()) out.add(n);
        return out;
    }

    private static AbstractInsnNode previousReal(List<AbstractInsnNode> insns, int start) {
        for (int i = start; i >= 0; i--) {
            int t = insns.get(i).getType();
            if (t != AbstractInsnNode.LABEL && t != AbstractInsnNode.LINE && t != AbstractInsnNode.FRAME) return insns.get(i);
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
            case Opcodes.SIPUSH: return ((IntInsnNode) n).operand;
            case Opcodes.LDC:
                Object cst = ((LdcInsnNode) n).cst;
                return cst instanceof Integer ? (Integer) cst : null;
            default: return null;
        }
    }

    private static String opcodeName(int opcode) {
        String[] names = org.objectweb.asm.util.Printer.OPCODES;
        return opcode >= 0 && opcode < names.length ? names[opcode] : String.valueOf(opcode);
    }

    private static final class Candidate {
        final String internalOwner;
        final String binaryOwner;
        final String name;
        final String desc;
        final boolean isStatic;
        Candidate(String internalOwner, String binaryOwner, String name, String desc, boolean isStatic) {
            this.internalOwner = internalOwner;
            this.binaryOwner = binaryOwner;
            this.name = name;
            this.desc = desc;
            this.isStatic = isStatic;
        }
    }

    private static final class Pair {
        final String enc;
        final String plain;
        Pair(String enc, String plain) {
            this.enc = enc;
            this.plain = plain;
        }
    }
}
