package ua.naiksoftware.stomp.client

import android.util.Log
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.disposables.Disposable
import io.reactivex.flowables.ConnectableFlowable
import ua.naiksoftware.stomp.ConnectionProvider
import ua.naiksoftware.stomp.LifecycleEvent
import ua.naiksoftware.stomp.StompHeader
import ua.naiksoftware.stomp.WebSocketsConnectionProvider
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.HashSet

/**
 * Created by naik on 05.05.16.
 */
class StompClient(private val mConnectionProvider: ConnectionProvider) {

    private var mMessagesDisposable: Disposable? = null
    private var mLifecycleDisposable: Disposable? = null
    private val mEmitters = ConcurrentHashMap<String, MutableSet<FlowableEmitter<in StompMessage>>>()
    private val mWaitConnectionFlowables: MutableList<ConnectableFlowable<Void>>
    private var mTopics: HashMap<String, String>? = null
    var isConnected: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set

    init {
        mWaitConnectionFlowables = CopyOnWriteArrayList()
    }

    fun connect(reconnect: Boolean) {
        connect(null, reconnect)
    }

    /**
     * If already connected and reconnect=false - nope
     *
     * @param _headers might be null
     */
    @JvmOverloads
    fun connect(_headers: List<StompHeader>? = null, reconnect: Boolean = false) {
        if (reconnect) disconnect()
        if (isConnected) return
        isConnecting = true
        mLifecycleDisposable = lifecycle()
                .subscribe { lifecycleEvent ->
                    Log.d(TAG, "Received lifecycle event $lifecycleEvent" )
                    when (lifecycleEvent.type) {
                        LifecycleEvent.Type.OPENED -> {
                            val headers = ArrayList<StompHeader>()
                            headers.add(StompHeader(StompHeader.VERSION, SUPPORTED_VERSIONS))
                            if (_headers != null) headers.addAll(_headers)
                            mConnectionProvider.send(StompMessage(StompCommand.CONNECT, headers, null).compile())
                                    .subscribe()
                        }

                        LifecycleEvent.Type.CLOSED, LifecycleEvent.Type.ERROR -> {
                            isConnected = false
                            isConnecting = false
                        }
                    }
                }
        mMessagesDisposable = mConnectionProvider.messages()
                .map<StompMessage> { StompMessage.from(it) }
                .subscribe { stompMessage ->
                    if (stompMessage.stompCommand == StompCommand.CONNECTED) {
                        isConnected = true
                        isConnecting = false
                        for (flowable in mWaitConnectionFlowables) {
                            flowable.connect()
                        }
                        mWaitConnectionFlowables.clear()

                    }
                    callSubscribers(stompMessage)
                }
    }

    fun send(destination: String): Flowable<Void> {
        return send(StompMessage(
                StompCommand.SEND,
                listOf(StompHeader(StompHeader.DESTINATION, destination)), null))
    }

    fun send(destination: String, data: String): Flowable<Void> {
        return send(StompMessage(
                StompCommand.SEND,
                listOf(StompHeader(StompHeader.DESTINATION, destination)),
                data))
    }

    fun send(stompMessage: StompMessage): Flowable<Void> {
        val flowable = mConnectionProvider.send(stompMessage.compile())
        if (!isConnected) {
            val deferred = flowable.publish()
            mWaitConnectionFlowables.add(deferred)
            return deferred
        } else {
            return flowable
        }
    }

    private fun callSubscribers(stompMessage: StompMessage) {
        val messageDestination = stompMessage.findHeader(StompHeader.DESTINATION)
        for (dest in mEmitters.keys) {
            if (dest == messageDestination) {
                val emitters = mEmitters[dest]
                if (emitters != null) {
                    for (subscriber in emitters) {
                        subscriber.onNext(stompMessage)
                    }
                }
                return
            }
        }
    }

    fun lifecycle(): Flowable<LifecycleEvent> {
        return mConnectionProvider.lifecycleReceiver
    }

    fun disconnect() {
        if (mMessagesDisposable != null) {
            mMessagesDisposable?.dispose()
        }
        if (mLifecycleDisposable != null) {
            mLifecycleDisposable?.dispose()
        }
        mEmitters.clear()
        mWaitConnectionFlowables.clear()
        isConnected = false
        mConnectionProvider.disconnect()
    }

    @JvmOverloads
    fun topic(destinationPath: String, headerList: List<StompHeader>? = null): Flowable<StompMessage> {
        return Flowable.create<StompMessage>({ emitter ->
            val emitters : MutableSet<FlowableEmitter<in StompMessage>> = mEmitters?.get(destinationPath) ?: mutableSetOf<FlowableEmitter<in StompMessage>>().apply{
                mEmitters.put(destinationPath, this)
                subscribePath(destinationPath, headerList).subscribe()
            }
            emitters.add(emitter)
        }, BackpressureStrategy.BUFFER)
                .doOnCancel {
                    val mapIterator = mEmitters.keys.iterator()
                    while (mapIterator.hasNext()) {
                        val destinationUrl = mapIterator.next()
                        val set = mEmitters[destinationUrl]
                        val setIterator = set?.iterator()
                        if (setIterator != null) {
                            while (setIterator.hasNext()) {
                                val subscriber = setIterator.next()
                                if (subscriber.isCancelled) {
                                    setIterator.remove()
                                    if (set.size < 1) {
                                        mapIterator.remove()
                                        unsubscribePath(destinationUrl).subscribe()
                                    }
                                }
                            }
                        }
                    }
                }
    }

    private fun subscribePath(destinationPath: String?, headerList: List<StompHeader>?): Flowable<Void> {
        if (destinationPath == null) return Flowable.empty()
        val topicId = UUID.randomUUID().toString()

        if (mTopics == null) mTopics = HashMap()
        mTopics?.put(destinationPath, topicId)
        val headers = ArrayList<StompHeader>()
        headers.add(StompHeader(StompHeader.ID, topicId))
        headers.add(StompHeader(StompHeader.DESTINATION, destinationPath))
        headers.add(StompHeader(StompHeader.ACK, DEFAULT_ACK))
        if (headerList != null) headers.addAll(headerList)
        return send(StompMessage(StompCommand.SUBSCRIBE,
                headers, null))
    }


    private fun unsubscribePath(dest: String): Flowable<Void> {
        val topicId = mTopics?.get(dest)
        Log.d(TAG, "Unsubscribe path: $dest id: $topicId")

        return send(StompMessage(StompCommand.UNSUBSCRIBE,
                listOf(StompHeader(StompHeader.ID, topicId)), null))
    }

    companion object {

        private val TAG = StompClient::class.java.simpleName

        private val SUPPORTED_VERSIONS = "1.1,1.0"
        private val DEFAULT_ACK = "auto"
    }
}
