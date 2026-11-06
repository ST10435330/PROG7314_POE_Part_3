package com.studysync.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TaskDateTextTest {
    @Test
    fun validDatesHaveReadableLabels() {
        assertEquals("26 Sep 2026", taskDateText("2026-09-26", Locale.ENGLISH))
        assertEquals("1 Jan 2027", taskDateText("2027-01-01", Locale.ENGLISH))
    }

    @Test
    fun invalidLegacyDatesRemainVisible() {
        assertEquals("2026-02-30", taskDateText("2026-02-30", Locale.ENGLISH))
        assertEquals("bad-date", taskDateText("bad-date", Locale.ENGLISH))
    }

    @Test
    fun missingDatesDoNotCrash() {
        assertEquals("", taskDateText("", Locale.ENGLISH))
        assertEquals("", taskDateText(null, Locale.ENGLISH))
    }
}
