package ua.naiksoftware.stomp

import android.util.Log

import java.util.ArrayList
import java.util.HashMap
import java.util.TreeMap

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

internal class OkHttpConnectionProvider(private val mUri: String, connectHttpHeaders: Map<String, String>?,
                                        private val mOkHttpClient: OkHttpClient) : ConnectionProvider {

    companion object {
        private val TAG = WebSocketsConnectionProvider::class.java.simpleName
    }

    private val mConnectHttpHeaders: Map<String, String> = connectHttpHeaders ?: emptyMap()

    private var openedSocked: WebSocket? = null

    private var mMessageEmitter: FlowableEmitter<in String>? = null
    private val mLifecycleEmitters = ArrayList<FlowableEmitter<in LifecycleEvent>>()

    override val lifecycleReceiver: Flowable<LifecycleEvent>
        get() = Flowable.create({ emitter ->
            synchronized(mLifecycleEmitters) {
                mLifecycleEmitters.add(emitter)
            }
        }, BackpressureStrategy.BUFFER)


    override fun messages(): Flowable<String> {
        val flowable = Flowable.create<String>({ emitter -> mMessageEmitter = emitter }, BackpressureStrategy.BUFFER)
        createWebSocketConnection()
        return flowable
    }


    private fun createWebSocketConnection() {

        if (openedSocked != null) {
            throw IllegalStateException("Already have connection to web socket")
        }

        val requestBuilder = Request.Builder()
                .url(mUri)

        addConnectionHeadersToBuilder(requestBuilder, mConnectHttpHeaders)

        openedSocked = mOkHttpClient.newWebSocket(requestBuilder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val openEvent = LifecycleEvent(LifecycleEvent.Type.OPENED)
                        emitLifecycleEvent(openEvent)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        emitMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        emitMessage(bytes.utf8())
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        emitLifecycleEvent(LifecycleEvent(LifecycleEvent.Type.CLOSED))
                        openedSocked = null
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        emitLifecycleEvent(LifecycleEvent(LifecycleEvent.Type.ERROR, Exception(t)))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }
        )
    }

    override fun send(stompMessage: String): Flowable<Void> {
        return Flowable.create({ subscriber ->
            if (openedSocked == null) {
                subscriber.onError(IllegalStateException("Not connected yet"))
            } else {
                Log.d(TAG, "Send STOMP message: " + stompMessage)
                openedSocked?.send(stompMessage)
                subscriber.onComplete()
            }
        }, BackpressureStrategy.BUFFER)
    }

    private fun closeSocket() {
        if (openedSocked != null) {
            openedSocked?.close(1000, "")
            openedSocked = null
        }
    }

    override fun disconnect() {
        closeSocket()
        mMessageEmitter = null
        synchronized(mLifecycleEmitters) {
            mLifecycleEmitters.clear()
        }
    }

    private fun addConnectionHeadersToBuilder(requestBuilder: Request.Builder, mConnectHttpHeaders: Map<String, String>) {
        for ((key, value) in mConnectHttpHeaders) {
            requestBuilder.addHeader(key, value)
        }
    }

    private fun emitLifecycleEvent(lifecycleEvent: LifecycleEvent) {
        synchronized(mLifecycleEmitters) {
            for (subscriber in mLifecycleEmitters) {
                subscriber.onNext(lifecycleEvent)
            }
        }
    }

    private fun emitMessage(stompMessage: String) {
        Log.d(TAG, "Emit STOMP message: " + stompMessage)
        val emitter = mMessageEmitter
        emitter?.onNext(stompMessage)
    }

}
