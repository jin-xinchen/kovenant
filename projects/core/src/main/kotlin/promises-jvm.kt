/*
 * Copyright (c) 2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * THE SOFTWARE.
 */

package nl.komponents.kovenant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun concretePromise<V : Any>(context: Context, callable: () -> V): Promise<V, Exception>
        = AsyncPromise(context, callable)

internal fun concretePromise<V : Any, R : Any>(context: Context, promise: Promise<V, Exception>, callable: (V) -> R): Promise<R, Exception>
        = ThenPromise(context, promise, callable)

internal fun concreteSuccessfulPromise<V : Any, E : Any>(context: Context, value: V): Promise<V, E> = SuccessfulPromise(context, value)

internal fun concreteFailedPromise<V : Any, E : Any>(context: Context, value: E): Promise<V, E> = FailedPromise(context, value)

private class SuccessfulPromise<V : Any, E : Any>(context: Context, value: V) : AbstractPromise<V, E>(context) {
    init {
        trySetSuccessResult(value)
    }

    // Could override the `fail` methods since there is nothing to add
    // but any change to those methods might be missed.
    // the callbacks essentially get ignored anyway
}

private class FailedPromise<V : Any, E : Any>(context: Context, value: E) : AbstractPromise<V, E>(context) {
    init {
        trySetFailResult(value)
    }

    // Could override the `success` methods since there is nothing to add
    // but any change to those methods might be missed.
    // the callbacks essentially get ignored anyway
}

private class ThenPromise<V : Any, R : Any>(context: Context,
                                promise: Promise<V, Exception>,
                                callable: (V) -> R) :
        SelfResolvingPromise<R, Exception>(context),
        CancelablePromise<R, Exception> {
    private volatile var task: (() -> Unit)? = null

    init {
        promise success {
            val wrapper = {
                try {
                    val result = callable(it)
                    resolve(result)
                } catch(e: Exception) {
                    reject(e)
                } finally {
                    //avoid leaking memory after a reject/resolve
                    task = null
                }
            }
            task = wrapper
            context.workerContext offer wrapper
        } fail {
            reject(it)
        }
    }

    override fun cancel(error: Exception): Boolean {
        val wrapper = task
        if (wrapper != null) {
            task = null //avoid memory leaking
            context.workerContext.dispatcher.tryCancel(wrapper)


            if (trySetFailResult(error)) {
                fireFail(error)
                return true
            }
        }

        return false
    }

}

private class AsyncPromise<V : Any>(context: Context, callable: () -> V) :
        SelfResolvingPromise<V, Exception>(context),
        CancelablePromise<V, Exception> {
    private volatile var task: (() -> Unit)?

    init {
        val wrapper = {
            try {
                val result = callable()
                resolve(result)
            } catch(e: Exception) {
                reject(e)
            } finally {
                //avoid leaking memory after a reject/resolve
                task = null
            }
        }
        task = wrapper
        context.workerContext offer wrapper
    }

    override fun cancel(error: Exception): Boolean {
        val wrapper = task
        if (wrapper != null) {
            task = null //avoid memory leaking
            context.workerContext.dispatcher.tryCancel(wrapper)


            if (trySetFailResult(error)) {
                fireFail(error)
                return true
            }
        }

        return false
    }
}

private abstract class SelfResolvingPromise<V : Any, E : Any>(context: Context) : AbstractPromise<V, E>(context) {
    protected fun resolve(value: V) {
        if (trySetSuccessResult(value)) {
            fireSuccess(value)
        }
        //no need to report multiple completion here.
        //manage this ourselves, can't happen
    }

    protected fun reject(error: E) {
        if (trySetFailResult(error)) {
            fireFail(error)
        }
        //no need to report multiple completion here.
        //manage this ourselves, can't happen
    }
}

private class DeferredPromise<V : Any, E : Any>(context: Context) : AbstractPromise<V, E>(context), Deferred<V, E> {
    override fun resolve(value: V) {
        if (trySetSuccessResult(value)) {
            fireSuccess(value)
        } else {
            multipleCompletion(value)
        }
    }

    override fun reject(error: E) {
        if (trySetFailResult(error)) {
            fireFail(error)
        } else {
            multipleCompletion(error)
        }
    }

    //Only call this method if we know resolving is eminent.
    private fun multipleCompletion(newValue: Any) {
        while (!isDoneInternal()) {
            Thread.yield()
        }
        context.multipleCompletion(rawValue(), newValue)
    }

    override val promise: Promise<V, E> = object : Promise<V, E> by this {}
}

private abstract class AbstractPromise<V : Any, E : Any>(override val context: Context) : Promise<V, E> {
    private val state = AtomicReference(State.PENDING)
    private val waitingThreads = AtomicInteger(0)

    @suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val mutex = waitingThreads as Object
    private val head = AtomicReference<CallbackContextNode<V, E>>(null)
    private volatile var result: Any? = null


    override fun success(context: DispatcherContext, callback: (value: V) -> Unit): Promise<V, E> {
        if (isFailureInternal()) return this

        //Bypass the queue if this promise is resolved and the queue is empty
        //no need to create excess nodes
        if (isSuccessInternal() && isEmptyCallbacks()) {
            context offer { callback(getAsValueResult()) }
            return this
        }

        addSuccessCb(context, callback)

        //possibly resolved already
        if (isSuccessInternal()) fireSuccess(getAsValueResult())

        return this
    }

    override fun fail(context: DispatcherContext, callback: (error: E) -> Unit): Promise<V, E> {
        if (isSuccessInternal()) return this

        //Bypass the queue if this promise is resolved and the queue is empty
        //no need to create excess nodes
        if (isFailureInternal() && isEmptyCallbacks()) {
            context offer { callback(getAsFailResult()) }
            return this
        }

        addFailCb(context, callback)

        //possibly rejected already
        if (isFailureInternal()) fireFail(getAsFailResult())

        return this
    }

    override fun always(context: DispatcherContext, callback: () -> Unit): Promise<V, E> {
        //Bypass the queue if this promise is resolved and the queue is empty
        //no need to create excess nodes
        if ((isSuccessInternal() || isFailureInternal()) && isEmptyCallbacks()) {
            context offer { callback() }
            return this
        }

        addAlwaysCb(context, callback)

        //possibly completed already
        when {
            isSuccessInternal() -> fireSuccess(getAsValueResult())
            isFailureInternal() -> fireFail((getAsFailResult()))
        }

        return this
    }

    override fun get(): V {
        if (!isDoneInternal()) {
            waitingThreads.incrementAndGet()
            try {
                synchronized(mutex) {
                    while (!isDoneInternal()) {
                        try {
                            mutex.wait()
                        } catch(e: InterruptedException) {
                            throw FailedException(e)
                        }
                    }
                }
            } finally {
                waitingThreads.decrementAndGet()
            }
        }

        if (isSuccessInternal()) {
            return getAsValueResult()
        } else {
            throw getAsFailResult().asException()
        }
    }

    override fun getError(): E {
        if (!isDoneInternal()) {
            waitingThreads.incrementAndGet()
            try {
                synchronized(mutex) {
                    while (!isDoneInternal()) {
                        try {
                            mutex.wait()
                        } catch(e: InterruptedException) {
                            throw FailedException(e)
                        }
                    }
                }
            } finally {
                waitingThreads.decrementAndGet()
            }
        }

        if (isFailureInternal()) {
            return getAsFailResult()
        } else {
            throw FailedException(getAsValueResult())
        }
    }

    fun fireSuccess(value: V) = popAll {
        node ->
        node.runSuccess(value)
    }

    fun fireFail(value: E) = popAll {
        node ->
        node.runFail(value)
    }

    private enum class State {
        PENDING,
        MUTATING,
        SUCCESS,
        FAIL
    }

    private enum class NodeState {
        CHAINED,
        POPPING,
        APPENDING
    }


    fun trySetSuccessResult(result: V): Boolean {
        if (state.get() != State.PENDING) return false
        if (state.compareAndSet(State.PENDING, State.MUTATING)) {
            this.result = result
            state.set(State.SUCCESS)
            notifyBlockedThreads()
            return true
        }
        return false
    }

    fun trySetFailResult(result: E): Boolean {

        if (state.get() != State.PENDING) return false
        if (state.compareAndSet(State.PENDING, State.MUTATING)) {
            this.result = result
            state.set(State.FAIL)
            notifyBlockedThreads()
            return true
        }
        return false
    }

    private fun notifyBlockedThreads() {
        if (waitingThreads.get() > 0) {
            synchronized(mutex) {
                mutex.notifyAll()
            }
        }
    }


    protected fun isDoneInternal(): Boolean {
        val currentState = state.get()
        return currentState == State.SUCCESS || currentState == State.FAIL
    }

    protected fun isSuccessInternal(): Boolean = state.get() == State.SUCCESS
    protected fun isFailureInternal(): Boolean = state.get() == State.FAIL

    override fun isDone(): Boolean = isDoneInternal()
    override fun isFailure(): Boolean = isFailureInternal()
    override fun isSuccess(): Boolean = isSuccessInternal()


    //For internal use only! Method doesn't check anything, just casts.
    suppress("UNCHECKED_CAST")
    private fun getAsValueResult(): V = result as V

    //For internal use only! Method doesn't check anything, just casts.
    suppress("UNCHECKED_CAST")
    private fun getAsFailResult(): E = result as E

    protected fun rawValue(): Any = result as Any

    private fun addSuccessCb(context: DispatcherContext, cb: (V) -> Unit) = addValueNode(SuccessCallbackContextNode<V, E>(context, cb))

    private fun addFailCb(context: DispatcherContext, cb: (E) -> Unit) = addValueNode(FailCallbackContextNode<V, E>(context, cb))

    private fun addAlwaysCb(context: DispatcherContext, cb: () -> Unit) = addValueNode(AlwaysCallbackContextNode<V, E>(context, cb))

    private fun createHeadNode(): CallbackContextNode<V, E> = EmptyCallbackContextNode()

    private fun addValueNode(node: CallbackContextNode<V, E>) {
        //ensure there is a head
        ensureHeadNode()

        while (true) {
            val tail = findTailNode()

            if (tail.nodeState.compareAndSet(NodeState.CHAINED, NodeState.APPENDING)) {
                if (tail.next == null) {
                    tail.next = node
                    tail.nodeState.set(NodeState.CHAINED)
                    return
                }
                tail.nodeState.set(NodeState.CHAINED)
            }
        }
    }

    private fun ensureHeadNode() {
        while (head.get() == null) {
            head.compareAndSet(null, createHeadNode())
        }
    }

    private fun findTailNode(): CallbackContextNode<V, E> {
        var tail = head.get()
        while (true) {
            val next = tail.next
            if (next == null) {
                return tail
            }
            tail = next
        }
    }

    private fun isEmptyCallbacks(): Boolean {
        val headNode = head.get()
        return headNode == null || headNode.next == null
    }


    private inline fun popAll(fn: (CallbackContext<V, E>) -> Unit) {
        val localHead = head.get()

        if (localHead != null) {
            do {
                val popper = localHead.next
                if (popper != null) {
                    if (localHead.nodeState.compareAndSet(NodeState.CHAINED, NodeState.POPPING)) {
                        if (popper.nodeState.compareAndSet(NodeState.CHAINED, NodeState.POPPING)) {
                            localHead.next = popper.next
                            localHead.nodeState.set(NodeState.CHAINED)
                            popper.next = null
                            fn(popper)
                        }
                        localHead.nodeState.set(NodeState.CHAINED)
                    }
                }
            } while (popper != null)
        }
    }

    private interface CallbackContext<V, E> {
        public fun runSuccess(value: V)

        public fun runFail(value: E)
    }

    private abstract class CallbackContextNode<V, E> : CallbackContext<V, E> {
        volatile var next: CallbackContextNode<V, E>? = null
        var nodeState = AtomicReference(NodeState.CHAINED)
    }

    private class EmptyCallbackContextNode<V, E> : CallbackContextNode<V, E>() {

        override fun runSuccess(value: V) {
            //ignore
        }

        override fun runFail(value: E) {
            //ignore
        }
    }

    private class AlwaysCallbackContextNode<V, E>(private val context: DispatcherContext,
                                                  private val fn: () -> Unit) : CallbackContextNode<V, E>() {
        override fun runSuccess(value: V) = context.offer { fn() }

        override fun runFail(value: E) = context.offer { fn() }
    }

    private class SuccessCallbackContextNode<V, E>(private val context: DispatcherContext,
                                                   private val fn: (V) -> Unit) : CallbackContextNode<V, E>() {

        override fun runSuccess(value: V) = context.offer { fn(value) }

        override fun runFail(value: E) {
            //ignore
        }
    }

    private class FailCallbackContextNode<V, E>(private val context: DispatcherContext,
                                                private val fn: (E) -> Unit) : CallbackContextNode<V, E>() {

        override fun runSuccess(value: V) {
            //ignore
        }

        override fun runFail(value: E) = context.offer { fn(value) }
    }

}

private fun <V : Any, E : Any> defaultGet(promise: Promise<V, E>): V {
    val latch = CountDownLatch(1)
    val e = AtomicReference<E>()
    val v = AtomicReference<V>()

    promise.success {
        v.set(it)
        latch.countDown()
    } fail {
        e.set(it)
        latch.countDown()
    }
    latch.await()
    val error = e.get()
    if (error != null) {
        throw error.asException()
    }
    return v.get()
}

private fun <V : Any, E : Any> defaultGetError(promise: Promise<V, E>): E {
    val latch = CountDownLatch(1)
    val e = AtomicReference<E>()
    val v = AtomicReference<V>()

    promise.success {
        v.set(it)
        latch.countDown()
    } fail {
        e.set(it)
        latch.countDown()
    }
    latch.await()
    val value = v.get()
    if (value != null) {
        throw FailedException(value)
    }
    return e.get()
}

// Function introduced solely to remain backwards compatible.
// The default implementation doesn't use these.
@deprecated("inefficient, to be removed in version 3.0.0")
private fun Promise<*, *>.defaultIsDone(): Boolean {
    val dispatcherCtx = DispatcherContext.create(DirectDispatcher.instance, context.callbackContext.errorHandler)
    var called = false
    always(dispatcherCtx) { called = true }
    return called
}

// Function introduced solely to remain backwards compatible.
// The default implementation doesn't use these.
@deprecated("inefficient, to be removed in version 3.0.0")
private fun Promise<*, *>.defaultIsFailure(): Boolean {
    val dispatcherCtx = DispatcherContext.create(DirectDispatcher.instance, context.callbackContext.errorHandler)
    var called = false
    fail(dispatcherCtx) { called = true }
    return called
}

// Function introduced solely to remain backwards compatible.
// The default implementation doesn't use these.
@deprecated("inefficient, to be removed in version 3.0.0")
private fun Promise<*, *>.defaultIsSuccess(): Boolean {
    val dispatcherCtx = DispatcherContext.create(DirectDispatcher.instance, context.callbackContext.errorHandler)
    var called = false
    success(dispatcherCtx) { called = true }
    return called
}

private fun <T : Any> T.asException(): Exception {
    return when (this) {
        is Exception -> this
        else -> FailedException(this)
    }
}