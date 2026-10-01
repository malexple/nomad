package org.nomad.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.nomad.core.Ids;

/**
 * An invitation is a link that carries everything needed to write to someone: nomad://invite?k=KEY&n=NAME&s=NODE
 * where KEY is the Ed25519 signing key (32 bytes), NAME a display name and NODE the address of the node,
 * each as unpadded base64url. It can be sent through any other messenger or shown as a QR code.
 * The link is public information: it contains no secrets.
 */
public final class Invite {
    private static final String PREFIX = "nomad://invite?";

    private Invite() {}

    public record Data(byte[] sigKey, String name, String serverUrl) {
        public String uid() {
            return Ids.uid(sigKey);
        }
    }

    public static String create(byte[] sigKey, String name, String serverUrl) {
        if (sigKey.length != 32) {
            throw new IllegalArgumentException("the signing key must be 32 bytes");
        }
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + "k=" + enc.encodeToString(sigKey)
                + "&n=" + enc.encodeToString(name.getBytes(StandardCharsets.UTF_8))
                + "&s=" + enc.encodeToString(serverUrl.getBytes(StandardCharsets.UTF_8));
    }

    /** Finds the link inside arbitrary pasted text. @throws IllegalArgumentException if there is no valid invitation */
    public static Data parse(String text) {
        int start = text.indexOf(PREFIX);
        if (start < 0) {
            throw new IllegalArgumentException("это не приглашение Nomad");
        }
        int end = start + PREFIX.length();
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String query = text.substring(start + PREFIX.length(), end);
        byte[] key = null;
        String name = "";
        String server = "";
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String field = part.substring(0, eq);
                String value = part.substring(eq + 1);
                switch (field) {
                    case "k" -> key = dec.decode(value);
                    case "n" -> name = new String(dec.decode(value), StandardCharsets.UTF_8);
                    case "s" -> server = new String(dec.decode(value), StandardCharsets.UTF_8);
                    default -> { }
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("приглашение повреждено", e);
        }
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("в приглашении нет ключа");
        }
        return new Data(key, name, server);
    }
}
