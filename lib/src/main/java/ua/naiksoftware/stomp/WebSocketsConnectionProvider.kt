package ua.naiksoftware.stomp

import android.util.Log

import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_17
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake

import java.net.URI
import java.util.ArrayList
import java.util.HashMap
import java.util.TreeMap

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.FlowableOnSubscribe

/**
 * Created by naik on 05.05.16.
 */
/**
 * Support UIR scheme ws://host:port/path
 *
 * @param connectHttpHeaders may be null
 */
internal class WebSocketsConnectionProvider (private val mUri: String, connectHttpHeaders: Map<String, String>?) : ConnectionProvider {

    private val mConnectHttpHeaders: Map<String, String> = connectHttpHeaders ?: emptyMap()

    private val mLifecycleEmitters: MutableList<FlowableEmitter<in LifecycleEvent>> = mutableListOf()
    private val mMessagesEmitters: MutableList<FlowableEmitter<in String>> = mutableListOf()

    private var mWebSocketClient: WebSocketClient? = null
    private var haveConnection: Boolean = false

    private val mLifecycleLock = Any()

    companion object {
        private val TAG = WebSocketsConnectionProvider::class.java.simpleName
    }

    override val lifecycleReceiver: Flowable<LifecycleEvent>
        get() = Flowable.create<LifecycleEvent>({ it ->
            synchronized(mLifecycleLock) {
                mLifecycleEmitters.add(it)
            }
            Log.d(TAG, "Lifecycle Add Emitter size => ${mLifecycleEmitters.size}" )
        }, BackpressureStrategy.BUFFER)
                .doOnCancel {
                    Log.d(TAG, "Lifecycle Cancel" )
                    synchronized(mLifecycleLock) {
                        mLifecycleEmitters.removeAll { it.isCancelled }
                    }
                }

    override fun messages(): Flowable<String> {
        val flowable = Flowable.create<String>({ mMessagesEmitters.add(it) }, BackpressureStrategy.BUFFER)
                .doOnCancel {
                    val iterator = mMessagesEmitters.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().isCancelled) iterator.remove()
                    }

                    if (mMessagesEmitters.size < 1) {
                        Log.d(TAG, "Close web socket connection now in thread " + Thread.currentThread())
                        mWebSocketClient?.close()
                    }
                }
        createWebSocketConnection()
        return flowable
    }

    private fun createWebSocketConnection() {
        if (haveConnection)
            throw IllegalStateException("Already have connection to web socket")

        mWebSocketClient = object : WebSocketClient(URI.create(mUri), Draft_17(), mConnectHttpHeaders, 0) {

            @Throws(InvalidDataException::class)
            override fun onWebsocketHandshakeReceivedAsClient(conn: WebSocket?, request: ClientHandshake?, response: ServerHandshake?) {
                Log.d(TAG, """Lifecycle : onWebSocketHandshakeReceivedAsClient with response: ${response?.httpStatus} ${response?.httpStatusMessage}""")
            }

            override fun onOpen(handshakeData: ServerHandshake) {
                Log.d(TAG, """Lifecycle : onOpen with handshakeData: ${handshakeData.httpStatus} ${handshakeData.httpStatusMessage}""")
                val openEvent = LifecycleEvent(LifecycleEvent.Type.OPENED)
                emitLifecycleEvent(openEvent)
            }

            override fun onMessage(message: String) {
                Log.d(TAG, "Lifecycle : onMessage: " + message)
                emitMessage(message)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.d(TAG, "Lifecycle : onClose: code=$code reason=$reason remote=$remote")
                haveConnection = false
                emitLifecycleEvent(LifecycleEvent(LifecycleEvent.Type.CLOSED))
            }

            override fun onError(ex: Exception) {
                Log.e(TAG, "Lifecycle : onError", ex)
                emitLifecycleEvent(LifecycleEvent(LifecycleEvent.Type.ERROR, ex))
            }
        }

        if (mUri.startsWith("wss")) {
            try {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, null, null)
                val factory = sc.socketFactory
                mWebSocketClient?.setSocket(factory.createSocket())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        mWebSocketClient?.connect()
        haveConnection = true
    }

    override fun send(stompMessage: String): Flowable<Void> {
        return Flowable.create({ emitter ->
            if (mWebSocketClient == null) {
                emitter.onError(IllegalStateException("Not connected yet"))
            } else {
                Log.d(TAG, "Send STOMP message: " + stompMessage)
                mWebSocketClient?.send(stompMessage)
                emitter.onComplete()
            }
        }, BackpressureStrategy.BUFFER)
    }

    private fun emitLifecycleEvent(lifecycleEvent: LifecycleEvent) {
        synchronized(mLifecycleLock) {
            Log.d(TAG, "Emit lifecycle event: ${lifecycleEvent.type?.name} for receivers ${mLifecycleEmitters.count()}" )
            mLifecycleEmitters.forEach{
                it.onNext(lifecycleEvent)
            }
        }
    }

    private fun emitMessage(stompMessage: String) {
        Log.d(TAG, "Emit STOMP message: $stompMessage")
        mMessagesEmitters.forEach{
            it.onNext(stompMessage)
        }
    }

    override fun disconnect() {
        mWebSocketClient?.close()
    }
}
