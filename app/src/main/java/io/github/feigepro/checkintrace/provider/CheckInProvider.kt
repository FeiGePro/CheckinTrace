package io.github.feigepro.checkintrace.provider

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole

interface CheckInProvider {
    suspend fun validateCredential(): Result<Unit>
    suspend fun getRoles(game: GameDefinition): Result<List<GameRole>>
    suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult
}
