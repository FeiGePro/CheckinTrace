package com.signin.assistant.provider

import com.signin.assistant.data.CheckInResult
import com.signin.assistant.data.GameDefinition
import com.signin.assistant.data.GameRole

interface CheckInProvider {
    suspend fun validateCredential(): Result<Unit>
    suspend fun getRoles(game: GameDefinition): Result<List<GameRole>>
    suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult
}

