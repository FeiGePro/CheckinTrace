package io.github.feigepro.checkintrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCheckInDecisionTest {
    @Test
    fun transientFailureRetriesOnlyOnce() {
        val first = decideAutoCheckInCompletion(
            hasFailures = true,
            requiresAction = false,
            hasRetryableFailure = true,
            runAttemptCount = 0,
        )
        val second = decideAutoCheckInCompletion(
            hasFailures = true,
            requiresAction = false,
            hasRetryableFailure = true,
            runAttemptCount = 1,
        )

        assertEquals(AutoCheckInRunState.RETRY_SCHEDULED, first.state)
        assertTrue(first.shouldRetry)
        assertEquals(AutoCheckInRunState.FAILED, second.state)
        assertFalse(second.shouldRetry)
    }

    @Test
    fun actionRequiredWinsAfterRetryLimit() {
        val decision = decideAutoCheckInCompletion(
            hasFailures = true,
            requiresAction = true,
            hasRetryableFailure = false,
            runAttemptCount = 0,
        )

        assertEquals(AutoCheckInRunState.ACTION_REQUIRED, decision.state)
        assertFalse(decision.shouldRetry)
    }

    @Test
    fun cleanRunIsSuccessful() {
        val decision = decideAutoCheckInCompletion(
            hasFailures = false,
            requiresAction = false,
            hasRetryableFailure = false,
            runAttemptCount = 0,
        )

        assertEquals(AutoCheckInRunState.SUCCESS, decision.state)
        assertFalse(decision.shouldRetry)
    }

    @Test
    fun completedRoleKeyIsStableRegardlessOfExtraMapOrder() {
        val first = autoCheckInRoleKey(
            gameId = "skland.endfield",
            uid = "uid-1",
            extra = linkedMapOf("serverId" to "2", "roleId" to "1"),
        )
        val second = autoCheckInRoleKey(
            gameId = "skland.endfield",
            uid = "uid-1",
            extra = linkedMapOf("roleId" to "1", "serverId" to "2"),
        )

        assertEquals(first, second)
    }

    @Test
    fun differentRolesDoNotShareCompletedRoleKey() {
        val first = autoCheckInRoleKey("skland.endfield", "uid-1", mapOf("roleId" to "1"))
        val second = autoCheckInRoleKey("skland.endfield", "uid-1", mapOf("roleId" to "2"))

        assertNotEquals(first, second)
    }
}
