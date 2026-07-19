package io.github.feigepro.checkintrace.provider.mihoyo

import io.github.feigepro.checkintrace.data.CheckInResult
import io.github.feigepro.checkintrace.data.GameDefinition
import io.github.feigepro.checkintrace.data.GameRole
import io.github.feigepro.checkintrace.provider.CheckInProvider

/** 米游社签到协议入口；登录凭证由 [MihoyoQrLoginClient] 取得。 */
class MihoyoProvider(
    private val credential: MihoyoCredentialBundle,
    private val api: MihoyoAttendanceApi = MihoyoAttendanceApi(credential),
) : CheckInProvider {
    override suspend fun validateCredential(): Result<Unit> = runCatching {
        require(credential.accountId.isNotBlank()) { "账号 ID 为空" }
        require(credential.stoken.isNotBlank()) { "stoken 为空" }
        require(credential.mid.isNotBlank()) { "mid 为空" }
    }

    override suspend fun getRoles(game: GameDefinition): Result<List<GameRole>> =
        api.getRoles(game)

    override suspend fun checkIn(game: GameDefinition, role: GameRole): CheckInResult =
        api.checkIn(game, role)
}
