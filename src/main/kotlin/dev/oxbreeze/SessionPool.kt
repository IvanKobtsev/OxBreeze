package dev.oxbreeze

/** Generation checks also guard results already queued for the UI thread. */
internal class SessionPool<K, V : AutoCloseable>(private val running: (V) -> Boolean, private val create: (K) -> V) {
    private val sessions = LinkedHashMap<K, V>()
    @Volatile var generation = 0L
        private set

    fun acquire(key: K, expectedGeneration: Long): V? {
        synchronized(this) {
            if (generation != expectedGeneration) return null
            sessions[key]?.takeIf(running)?.let { return it }
        }
        // Process creation must not hold the lock used by UI restart operations.
        val created = create(key)
        var previous: V? = null
        val result = synchronized(this) {
            if (generation != expectedGeneration) null
            else sessions[key]?.takeIf(running) ?: created.also { previous = sessions.put(key, it) }
        }
        previous?.close()
        if (result !== created) created.close()
        return result
    }

    @Synchronized fun remove(key: K, session: V) { sessions.remove(key, session) }
    @Synchronized fun activeCount() = sessions.values.count(running)

    // Invalidate immediately; callers close the returned sessions on a background thread.
    @Synchronized fun invalidate(): List<V> {
        generation++
        return sessions.values.toList().also { sessions.clear() }
    }
}
