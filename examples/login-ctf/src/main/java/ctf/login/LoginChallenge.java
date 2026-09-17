package ctf.login;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deliberately small login challenge used to compare original and obfuscated
 * program behavior. This is a CTF fixture, not a production authentication
 * implementation.
 */
public final class LoginChallenge {
    private static final String USER_SHA_256 =
            "49ead2b1066bda1e127f6ae0bd163778d08587e3e97d9ed58e8cc99972460a1c";
    private static final String PASSWORD_SHA_256 =
            "b7621a258979757720fab1e4bd09e730acaabf7d129c3913427092147da0c122";

    private LoginChallenge() {
    }

    public static void main(String[] args) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int exitCode = run(input, System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(BufferedReader input, PrintStream output) throws IOException {
        output.println("Skidfuscator Login CTF");
        output.println("Username:");
        String username = input.readLine();
        if (username == null) {
            output.println("Input error.");
            return 2;
        }

        output.println("Password:");
        String password = input.readLine();
        if (password == null) {
            output.println("Input error.");
            return 2;
        }

        if (!authenticate(username, password)) {
            output.println("Access denied.");
            return 1;
        }

        output.println("Access granted.");
        output.println("FLAG=" + revealFlag());
        return 0;
    }

    private static boolean authenticate(String username, String password) {
        byte[] suppliedUser = sha256(username);
        byte[] suppliedPassword = sha256(password);
        byte[] expectedUser = decodeHex(USER_SHA_256);
        byte[] expectedPassword = decodeHex(PASSWORD_SHA_256);

        // Evaluate both comparisons so the result does not short-circuit after
        // learning whether only the username matched.
        boolean userMatches = MessageDigest.isEqual(suppliedUser, expectedUser);
        boolean passwordMatches = MessageDigest.isEqual(suppliedPassword, expectedPassword);
        return userMatches & passwordMatches;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] decodeHex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex input must have an even length");
        }

        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal input");
            }
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static String revealFlag() {
        return "SKID{maple_ir_obfuscation_verified}";
    }
}
