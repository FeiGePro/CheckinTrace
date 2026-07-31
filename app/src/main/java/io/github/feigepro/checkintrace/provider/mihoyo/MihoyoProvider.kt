package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.provider.CheckInProvider

/** 米游社 Android 签到入口。 */
class MihoyoProvider(
    private val credential: MihoyoCredentialBundle,
    private val api: MihoyoAttendanceApi = MihoyoAttendanceApi(credential),
) : CheckInProvider {
    override suspend fun validateCredential(): Result<Unit> = runCatching {
        require(credential.accountId.isNotBlank()) { "账号 ID 为空" }
        require(credential.stoken.isNotBlank()) { "stoken 为空" }
        require(credential.mid.isNotBlank()) { "mid 为空" }
        require(!credential.cookieToken.isNullOrBlank()) { "cookie_token 为空，请重新登录" }
        api.registerAndroidDevice().getOrThrow()
    }

    override suspend fun getRoles(game: GameDefinition): Result<List<GameRole>> =
        api.getRoles(game)

    override suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult =
        api.checkIn(game, role)
}
