package ua.naiksoftware.stomp

/**
 * Created by naik on 05.05.16.
 */
class LifecycleEvent {

    var type: Type? = null
    var exception: Throwable? = null

    constructor(type: Type) {
        this.type = type
    }

    constructor(type: Type, throwable: Throwable) {
        this.type = type
        this.exception = throwable
    }

    enum class Type {
        OPENED,
        CLOSED,
        ERROR
    }
}
