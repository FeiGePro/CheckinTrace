package io.github.feigepro.checkintrace.provider

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole

interface CheckInProvider {
    suspend fun validateCredential(): Result<Unit>
    suspend fun getRoles(game: GameDefinition): Result<List<GameRole>>
    suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult
}

/**
 * A provider returned a structured failure that should not be inferred from a
 * localized exception message. Keeping the code here lets the scheduler stop
 * a provider immediately when the platform asks for login or human action.
 */
class ProviderFailureException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
