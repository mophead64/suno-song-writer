package app.sunosongwriter.domain.usage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps requests under a per-minute limit using a sliding 60-second window per key (spec 5.2).
 *
 * [reserve] books the next free slot and returns how long to wait for it, so parallel callers that
 * share a backend are staggered rather than all firing and getting 429s.
 */
class RequestPacer(private val nowMillis: () -> Long) {
    private val mutex = Mutex()
    private val windows = HashMap<String, ArrayDeque<Long>>()

    /** Milliseconds to wait before sending. 0 when there's no limit or a slot is free now. */
    suspend fun reserve(key: String, requestsPerMinute: Int?): Long {
        if (requestsPerMinute == null || requestsPerMinute <= 0) return 0
        return mutex.withLock {
            val now = nowMillis()
            val slots = windows.getOrPut(key) { ArrayDeque() }
            while (slots.isNotEmpty() && slots.first() <= now - WINDOW_MILLIS) slots.removeFirst()
            val slot = if (slots.size < requestsPerMinute) now else slots[slots.size - requestsPerMinute] + WINDOW_MILLIS
            slots.addLast(maxOf(slot, now))
            maxOf(slot - now, 0)
        }
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}
