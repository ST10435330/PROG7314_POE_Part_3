package com.studysync.app

/** One-use permission to resume the same unlocked account after a document result. */
internal class DocumentPickerSession(private val now: () -> Long) {
    private var owner: String? = null
    private var started = 0L

    fun begin(userId: String, unlocked: Boolean): Boolean {
        clear()
        if (!unlocked) return false
        owner = userId
        started = now()
        return true
    }

    fun finish(currentUserId: String?, deviceLocked: Boolean): String? {
        val userId = owner
        val age = now() - started
        clear()
        return userId?.takeIf {
            it == currentUserId && !deviceLocked && age in 0..120_000L
        }
    }

    fun clear() { owner = null; started = 0L }
}
