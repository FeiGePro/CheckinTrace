package io.github.feigepro.checkintrace

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCheckInSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `submitted roles survive serialization`() {
        val snapshot = AutoCheckInSnapshot(
            state = AutoCheckInRunState.RUNNING,
            startedAtEpochMillis = 1L,
            completedRoleKeys = setOf("completed"),
            submittedRoleKeys = setOf("completed", "possibly-sent"),
        )

        val restored = json.decodeFromString<AutoCheckInSnapshot>(json.encodeToString(snapshot))

        assertEquals(snapshot, restored)
    }

    @Test
    fun `legacy snapshot defaults submitted roles to empty`() {
        val restored = json.decodeFromString<AutoCheckInSnapshot>(
            """{"state":"RUNNING","startedAtEpochMillis":1,"completedRoleKeys":["completed"]}""",
        )

        assertEquals(setOf("completed"), restored.completedRoleKeys)
        assertTrue(restored.submittedRoleKeys.isEmpty())
    }
}
