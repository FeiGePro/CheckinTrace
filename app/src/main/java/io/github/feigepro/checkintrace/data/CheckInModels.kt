package io.github.feigepro.checkintrace.data

data class GameRole(
    val gameId: String,
    val uid: String,
    val nickname: String,
    val channelName: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

sealed interface CheckInResult {
    data class Success(val message: String, val rewards: List<String> = emptyList()) : CheckInResult
    data object AlreadyCheckedIn : CheckInResult
    data class Failure(val code: String, val message: String, val retryable: Boolean = false) : CheckInResult
}
