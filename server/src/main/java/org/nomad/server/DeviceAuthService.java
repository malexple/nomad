package org.nomad.server;

import jakarta.servlet.http.HttpServletRequest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import org.nomad.core.DeviceAuth;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies X-Device-Key (base64 raw Ed25519), X-Timestamp (unix seconds, +-60 s)
 * and X-Signature over DeviceAuth.canonical(method, path?query, ts, body).
 */
@Component
class DeviceAuthService {
    private static final long WINDOW_SECONDS = 60;

    record Authed(PublicKey key, byte[] rawKey) {}

    Authed verify(HttpServletRequest req, byte[] body) {
        String keyB64 = req.getHeader("X-Device-Key");
        String ts = req.getHeader("X-Timestamp");
        String sig = req.getHeader("X-Signature");
        if (keyB64 == null || ts == null || sig == null) {
            throw unauthorized("missing auth headers");
        }
        try {
            long t = Long.parseLong(ts);
            if (Math.abs(Instant.now().getEpochSecond() - t) > WINDOW_SECONDS) {
                throw unauthorized("timestamp out of window");
            }
            byte[] raw = Base64.getDecoder().decode(keyB64);
            PublicKey key = DeviceAuth.publicKeyFromRaw(raw);
            String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
            String canon = DeviceAuth.canonical(req.getMethod(), path, t, body);
            if (!DeviceAuth.verify(key, canon, Base64.getDecoder().decode(sig))) {
                throw unauthorized("bad signature");
            }
            return new Authed(key, raw);
        } catch (IllegalArgumentException e) {
            throw unauthorized("malformed auth headers");
        }
    }

    private static ResponseStatusException unauthorized(String msg) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, msg);
    }
}
