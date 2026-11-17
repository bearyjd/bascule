package com.ventouxlabs.bascule.service

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldReadingActionReceiverTest {

    private suspend fun daoWith(status: ReadingStatus) = FakeReadingDao().also {
        it.insert(readingFixture(id = "r1", status = status, retryEpochMillis = 1L, attemptCount = 3, lastError = "x"))
    }

    @Test
    fun heldConfirmConfirmActionTransitionsToPending() = runTest {
        val dao = daoWith(ReadingStatus.HELD_CONFIRM)

        assertTrue(resolveHeldReading(dao, "r1", confirm = true, nowMillis = 99L))

        val row = dao.byId("r1")!!
        assertEquals(ReadingStatus.PENDING, row.status)
        assertEquals(99L, row.retryEpochMillis)
        assertEquals(0, row.attemptCount)
        assertNull(row.lastError)
    }

    @Test
    fun heldConfirmDeclineActionTransitionsToDeclined() = runTest {
        val dao = daoWith(ReadingStatus.HELD_CONFIRM)

        assertFalse(resolveHeldReading(dao, "r1", confirm = false, nowMillis = 99L))

        assertEquals(ReadingStatus.DECLINED, dao.byId("r1")!!.status)
    }

    @Test
    fun aStaleTapOnAnAlreadyAnsweredRowChangesNothing() = runTest {
        val dao = daoWith(ReadingStatus.DECLINED)

        assertFalse(resolveHeldReading(dao, "r1", confirm = true, nowMillis = 99L))

        assertEquals(ReadingStatus.DECLINED, dao.byId("r1")!!.status)
    }

    @Test
    fun aMissingRowIsANoOp() = runTest {
        assertFalse(resolveHeldReading(FakeReadingDao(), "nope", confirm = true, nowMillis = 1L))
    }
}
