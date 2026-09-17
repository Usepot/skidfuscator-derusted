package dev.skidfuscator.nativetoolchain.vm;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Small XChaCha20-Poly1305 construction used for independently authenticated VM blocks.
 * XChaCha derives a one-time sub-key with HChaCha20 and then uses the JCA
 * ChaCha20-Poly1305 primitive with a 96-bit nonce.
 */
public final class XChaCha20Poly1305 {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int TAG_BYTES = 16;

    public byte[] seal(final byte[] key, final byte[] nonce, final byte[] aad, final byte[] plaintext) {
        return crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
    }

    public byte[] open(final byte[] key, final byte[] nonce, final byte[] aad, final byte[] ciphertext)
            throws AEADBadTagException {
        try {
            return crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext);
        } catch (AuthenticationFailure failure) {
            final AEADBadTagException exception = new AEADBadTagException("VM block authentication failed");
            exception.initCause(failure.getCause());
            throw exception;
        }
    }

    private static byte[] crypt(
            final int mode,
            final byte[] key,
            final byte[] nonce,
            final byte[] aad,
            final byte[] input
    ) {
        requireLength(key, KEY_BYTES, "key");
        requireLength(nonce, NONCE_BYTES, "nonce");
        Objects.requireNonNull(aad, "aad");
        Objects.requireNonNull(input, "input");

        final byte[] subKey = hChaCha20(key, Arrays.copyOf(nonce, 16));
        final byte[] jcaNonce = new byte[12];
        System.arraycopy(nonce, 16, jcaNonce, 4, 8);
        try {
            final Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(mode, new SecretKeySpec(subKey, "ChaCha20"), new IvParameterSpec(jcaNonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            if (mode == Cipher.DECRYPT_MODE && exception instanceof AEADBadTagException) {
                throw new AuthenticationFailure(exception);
            }
            throw new IllegalStateException("ChaCha20-Poly1305 is unavailable", exception);
        } finally {
            Arrays.fill(subKey, (byte) 0);
            Arrays.fill(jcaNonce, (byte) 0);
        }
    }

    private static byte[] hChaCha20(final byte[] key, final byte[] nonce) {
        final int[] state = {
                0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                littleInt(key, 0), littleInt(key, 4), littleInt(key, 8), littleInt(key, 12),
                littleInt(key, 16), littleInt(key, 20), littleInt(key, 24), littleInt(key, 28),
                littleInt(nonce, 0), littleInt(nonce, 4), littleInt(nonce, 8), littleInt(nonce, 12)
        };
        for (int round = 0; round < 10; round++) {
            quarter(state, 0, 4, 8, 12);
            quarter(state, 1, 5, 9, 13);
            quarter(state, 2, 6, 10, 14);
            quarter(state, 3, 7, 11, 15);
            quarter(state, 0, 5, 10, 15);
            quarter(state, 1, 6, 11, 12);
            quarter(state, 2, 7, 8, 13);
            quarter(state, 3, 4, 9, 14);
        }
        final byte[] output = new byte[KEY_BYTES];
        putLittle(output, 0, state[0]);
        putLittle(output, 4, state[1]);
        putLittle(output, 8, state[2]);
        putLittle(output, 12, state[3]);
        putLittle(output, 16, state[12]);
        putLittle(output, 20, state[13]);
        putLittle(output, 24, state[14]);
        putLittle(output, 28, state[15]);
        Arrays.fill(state, 0);
        return output;
    }

    private static void quarter(final int[] x, final int a, final int b, final int c, final int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static int littleInt(final byte[] input, final int offset) {
        return (input[offset] & 0xff) | ((input[offset + 1] & 0xff) << 8)
                | ((input[offset + 2] & 0xff) << 16) | (input[offset + 3] << 24);
    }

    private static void putLittle(final byte[] output, final int offset, final int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static void requireLength(final byte[] value, final int length, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) throw new IllegalArgumentException(name + " must be " + length + " bytes");
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private AuthenticationFailure(final Throwable cause) { super(cause); }
    }
}
