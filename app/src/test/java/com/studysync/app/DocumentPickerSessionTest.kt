package com.studysync.app

import org.junit.Assert.*
import org.junit.Test

class DocumentPickerSessionTest {
    private var time = 100L
    private val session = DocumentPickerSession { time }

    @Test fun sameAccountReturnsOnce() {
        assertTrue(session.begin("alice", true))
        time += 1000
        assertEquals("alice", session.finish("alice", false))
        assertNull(session.finish("alice", false))
    }

    @Test fun lockedSessionsCannotStart() {
        session.begin("alice", true)
        assertFalse(session.begin("alice", false))
        assertNull(session.finish("alice", false))
    }

    @Test fun anotherAccountCannotResume() {
        session.begin("alice", true)
        assertNull(session.finish("bob", false))
        assertNull(session.finish("alice", false))
        session.begin("alice", true)
        assertNull(session.finish(null, false))
    }

    @Test fun longAbsencesRequireUnlock() {
        session.begin("alice", true)
        time += 120_001
        assertNull(session.finish("alice", false))
        session.begin("alice", true)
        time--
        assertNull(session.finish("alice", false))
    }

    @Test fun deviceLockAndExplicitLockCancelReturn() {
        session.begin("alice", true)
        assertNull(session.finish("alice", true))
        session.begin("alice", true)
        session.clear()
        assertNull(session.finish("alice", false))
    }

    @Test fun recreatedSessionHasNoReturnPermission() {
        session.begin("alice", true)
        assertNull(DocumentPickerSession { time }.finish("alice", false))
    }
}
