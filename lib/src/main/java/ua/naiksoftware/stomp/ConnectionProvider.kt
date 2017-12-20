package ua.naiksoftware.stomp

import io.reactivex.Flowable

/**
 * Created by naik on 05.05.16.
 */
interface ConnectionProvider {

    /**
     * Subscribe this for receive #LifecycleEvent events
     */
    val lifecycleReceiver: Flowable<LifecycleEvent>

    /**
     * Subscribe this for receive stomp messages
     */
    fun messages(): Flowable<String>

    /**
     * Sending stomp messages via you ConnectionProvider.
     * onError if not connected or error detected will be called, or onCompleted id sending started
     * TODO: send messages with ACK
     */
    fun send(stompMessage: String): Flowable<Void>

    /**
     * Disconnect the socket
     */
    fun disconnect()
}
