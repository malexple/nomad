package org.nomad.app

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.nomad.client.Transport
import org.nomad.client.TransportListener

/**
 * The Android side of the engine's Transport, the twin of the simulator's JdkWebSocketTransport.
 * OkHttp keeps the frames of one socket in order and sends a ping every 25 s, so a dead connection is noticed.
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build(),
) : Transport {

    @Volatile
    private var socket: WebSocket? = null

    override fun connect(url: String, listener: TransportListener) {
        close()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed("closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onClosed("failure: $t")
            }
        })
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}
