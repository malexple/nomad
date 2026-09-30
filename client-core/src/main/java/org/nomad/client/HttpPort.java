package org.nomad.client;

import java.io.IOException;
import java.util.Map;

/** Platform-neutral HTTP: java.net.http on desktop, OkHttp/Ktor on Android. */
public interface HttpPort {
    record Response(int status, byte[] body) {}

    Response execute(String method, String pathAndQuery, Map<String, String> headers, byte[] body)
            throws IOException;
}
