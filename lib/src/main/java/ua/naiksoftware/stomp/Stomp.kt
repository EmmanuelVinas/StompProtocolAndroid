package ua.naiksoftware.stomp

import org.java_websocket.WebSocket

import okhttp3.OkHttpClient
import ua.naiksoftware.stomp.client.StompClient

/**
 * Supported overlays:
 * - org.java_websocket.WebSocket ('org.java-websocket:Java-WebSocket:1.3.0')
 * - okhttp3.WebSocket ('com.squareup.okhttp3:okhttp:3.8.0')
 *
 * You can add own relay, just implement ConnectionProvider for you stomp transport,
 * such as web socket.
 *
 * Created by naik on 05.05.16.
 */
object Stomp {

    /**
     * `webSocketClient` can accept the following type of clients:
     *
     *  * `org.java_websocket.WebSocket`: cannot accept an existing client
     *  * `okhttp3.WebSocket`: can accept a non-null instance of `okhttp3.OkHttpClient`
     *
     * @param clazz class for using as transport
     * @param uri URI to connect
     * @param connectHttpHeaders HTTP headers, will be passed with handshake query, may be null
     * @param webSocketClient Existing client that will be used to open the WebSocket connection, may be null to use default client
     * @return StompClient for receiving and sending messages. Call #StompClient.connect
     */
    @JvmOverloads
    fun over(clazz: Class<*>, uri: String, connectHttpHeaders: Map<String, String>? = null, webSocketClient: Any? = null): StompClient {
        try {
            if (Class.forName("org.java_websocket.WebSocket") != null && clazz == WebSocket::class.java) {

                if (webSocketClient != null) {
                    throw IllegalArgumentException("You cannot pass a webSocketClient with 'org.java_websocket.WebSocket'. use null instead.")
                }

                return createStompClient(WebSocketsConnectionProvider(uri, connectHttpHeaders))
            }
        } catch (e: ClassNotFoundException) {
        }

        try {
            if (Class.forName("okhttp3.WebSocket") != null && clazz == okhttp3.WebSocket::class.java) {

                val okHttpClient = getOkHttpClient(webSocketClient)

                return createStompClient(OkHttpConnectionProvider(uri, connectHttpHeaders, okHttpClient))
            }
        } catch (e: ClassNotFoundException) {
        }

        throw RuntimeException("Not supported overlay transport: " + clazz.name)
    }

    private fun createStompClient(connectionProvider: ConnectionProvider): StompClient {
        return StompClient(connectionProvider)
    }

    private fun getOkHttpClient(webSocketClient: Any?): OkHttpClient {
        return if (webSocketClient != null) {
            webSocketClient as? OkHttpClient ?: throw IllegalArgumentException("You must pass a non-null instance of an 'okhttp3.OkHttpClient'. Or pass null to use a default websocket client.")
        } else {
            // default http client
            OkHttpClient()
        }
    }
}

