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

    /**
     * 请求已经提交，但客户端没有得到足以确认结果的响应。此状态不能自动重试，
     * 否则可能在服务端已经签到成功的情况下重复提交。
     */
    data class Unknown(val message: String) : CheckInResult

    data class Failure(val code: String, val message: String, val retryable: Boolean = false) : CheckInResult
}
