package demo;

public class FullPassMain {
    private static final String STATIC_SECRET = "LicenseGate authentication succeeded.";
    private static final String FLAG_PREFIX = "CTF{";

    public static void main(String[] args) {
        String user = args.length == 0 ? "skid" : args[0];
        int score = score(user);
        String message = buildMessage(user, score);

        if (message.contains("LicenseGate") && message.startsWith("User=")) {
            System.out.println(message);
            System.out.println(FLAG_PREFIX + Integer.toHexString(message.hashCode()) + "}");
        } else {
            throw new IllegalStateException("unexpected output");
        }
    }

    private static int score(String input) {
        int value = 0x51F15EED;
        for (int i = 0; i < input.length(); i++) {
            value ^= input.charAt(i) * 31;
            value = Integer.rotateLeft(value, 3) ^ 0x13579BDF;
        }
        return value;
    }

    private static String buildMessage(String user, int score) {
        String state = (score & 1) == 0 ? "EVEN" : "ODD";
        return "User=" + user + "; state=" + state + "; msg=" + STATIC_SECRET;
    }
}
