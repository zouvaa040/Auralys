package com.metrolist.music.video

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * A dispatcher with a clock the test moves by hand: `delay(n)` in the code under test waits until [advanceBy] has moved
 * the clock by n, and nothing runs until [runCurrent] or [advanceBy] is called. (kotlinx-coroutines-test is not a test
 * dependency of the app.)
 */
@OptIn(InternalCoroutinesApi::class)
class VirtualTimeDispatcher :
    CoroutineDispatcher(),
    Delay {
    private class Task(
        val at: Long,
        val seq: Long,
        val block: Runnable,
    )

    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L

    var time = 0L
        private set

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.add(Task(time, seq++, block))
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val task = Task(time + timeMillis, seq++, Runnable { continuation.resume(Unit) })
        tasks.add(task)
        continuation.invokeOnCancellation { tasks.remove(task) }
    }

    /** Runs everything that is due now. */
    fun runCurrent() = advanceBy(0L)

    /** Moves the clock by [ms], running every task that falls due on the way, in order. */
    fun advanceBy(ms: Long) {
        val target = time + ms
        while (true) {
            val next = tasks.peek() ?: break
            if (next.at > target) break
            tasks.poll()
            time = maxOf(time, next.at)
            next.block.run()
        }
        time = target
    }
}
