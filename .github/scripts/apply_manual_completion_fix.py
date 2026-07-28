from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one literal match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected one regex match, found {count}")
    return updated


# Remove the unsupported SMS/AIGIS login path completely.
for file_name in [
    "app/src/main/java/io/github/feigepro/checkintrace/MihoyoCaptchaLoginActivity.kt",
    "app/src/main/java/io/github/feigepro/checkintrace/provider/mihoyo/MihoyoCaptchaLoginClient.kt",
    "app/src/test/java/io/github/feigepro/checkintrace/MihoyoAigisHtmlTest.kt",
    "app/src/test/java/io/github/feigepro/checkintrace/provider/mihoyo/MihoyoCaptchaLoginClientTest.kt",
]:
    Path(file_name).unlink(missing_ok=True)

manifest_path = "app/src/main/AndroidManifest.xml"
manifest = read(manifest_path)
manifest = regex_once(
    manifest,
    r"\n\s*<activity\s*\n\s*android:name=\"\.MihoyoCaptchaLoginActivity\"\s*\n\s*android:exported=\"false\"\s*/>\s*",
    "\n",
    "remove SMS activity",
)
write(manifest_path, manifest)

activity_path = "app/src/main/java/io/github/feigepro/checkintrace/MainActivity.kt"
activity = read(activity_path).replace("import android.content.Intent\n", "")
activity = regex_once(
    activity,
    r"\n\s*onMihoyoSmsLogin\s*=\s*\{\s*context\.startActivity\(Intent\(context,\s*MihoyoCaptchaLoginActivity::class\.java\)\)\s*},",
    "",
    "remove SMS launch callback",
)
activity = regex_once(
    activity,
    r"\n\s*onMihoyoSmsLogin:\s*\(\)\s*->\s*Unit,",
    "",
    "remove account panel SMS parameter",
)
activity = regex_once(
    activity,
    r"\n\s*onSmsLogin\s*=\s*onMihoyoSmsLogin,",
    "",
    "remove SMS row callback",
)
activity = regex_once(
    activity,
    r"@Composable\nprivate fun MihoyoAccountRow\(.*?\n}\n\n@Composable\nprivate fun AccountRow",
    '''@Composable
private fun MihoyoAccountRow(
    loggedIn: Boolean,
    enabled: Boolean,
    onQrLogin: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountIdentity("米游社", loggedIn, Modifier.weight(1f))
        Button(
            onClick = onQrLogin,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (loggedIn) "重新扫码" else "二维码登录") }
    }
}

@Composable
private fun AccountRow''',
    "replace miHoYo account row",
)
write(activity_path, activity)

# A local-date-scoped completion marker shared by manual and WorkManager runs.
write(
    "app/src/main/java/io/github/feigepro/checkintrace/DailyCheckInProgressStore.kt",
    '''package io.github.feigepro.checkintrace

import android.content.Context
import java.time.LocalDate

internal data class DailyCheckInProgress(
    val date: String,
    val completedGameIds: Set<String> = emptySet(),
) {
    fun normalizedFor(today: String): DailyCheckInProgress =
        if (date == today) this else DailyCheckInProgress(date = today)
}

/**
 * Manual and scheduled check-ins share today's definitive game completion state.
 * A new local calendar date automatically starts with an empty record.
 */
internal class DailyCheckInProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadToday(today: LocalDate = LocalDate.now()): DailyCheckInProgress {
        val date = today.toString()
        return DailyCheckInProgress(
            date = preferences.getString(KEY_DATE, null).orEmpty(),
            completedGameIds = preferences.getStringSet(KEY_COMPLETED_GAMES, emptySet()).orEmpty().toSet(),
        ).normalizedFor(date)
    }

    @Synchronized
    fun markGameCompleted(gameId: String) {
        val current = loadToday()
        val updated = current.copy(completedGameIds = current.completedGameIds + gameId)
        check(
            preferences.edit()
                .putString(KEY_DATE, updated.date)
                .putStringSet(KEY_COMPLETED_GAMES, updated.completedGameIds.toSet())
                .commit(),
        ) { "无法保存今日签到完成状态" }
    }

    private companion object {
        const val PREFERENCES_NAME = "daily_checkin_progress"
        const val KEY_DATE = "date"
        const val KEY_COMPLETED_GAMES = "completed_games"
    }
}
''',
)

write(
    "app/src/test/java/io/github/feigepro/checkintrace/DailyCheckInProgressTest.kt",
    '''package io.github.feigepro.checkintrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCheckInProgressTest {
    @Test
    fun `same date keeps completed games`() {
        val progress = DailyCheckInProgress(
            date = "2026-07-28",
            completedGameIds = setOf("genshin"),
        )

        assertEquals(progress, progress.normalizedFor("2026-07-28"))
    }

    @Test
    fun `new date clears yesterday completion`() {
        val progress = DailyCheckInProgress(
            date = "2026-07-27",
            completedGameIds = setOf("genshin"),
        ).normalizedFor("2026-07-28")

        assertEquals("2026-07-28", progress.date)
        assertTrue(progress.completedGameIds.isEmpty())
    }
}
''',
)

view_model_path = "app/src/main/java/io/github/feigepro/checkintrace/MainViewModel.kt"
view_model = read(view_model_path)
view_model = replace_once(
    view_model,
    "    private val autoCheckInStatusStore = AutoCheckInStatusStore(application)\n",
    "    private val autoCheckInStatusStore = AutoCheckInStatusStore(application)\n" +
    "    private val dailyCheckInProgressStore = DailyCheckInProgressStore(application)\n",
    "add daily progress store",
)
view_model = view_model.replace(
    "请使用米游社 App 扫码并确认。该方式会显示为电脑/通行证登录；短信验证码仍作为备用方式。",
    "请使用米游社 App 扫码并确认电脑/通行证登录。",
)
view_model = replace_once(
    view_model,
    '''                    val roles = roleResult.getOrThrow()
                    if (roles.isEmpty()) lines += "${nowLabel()} ${game.displayName}：没有找到绑定角色"
                    for (role in roles) {''',
    '''                    val roles = roleResult.getOrThrow()
                    if (roles.isEmpty()) lines += "${nowLabel()} ${game.displayName}：没有找到绑定角色"
                    var allRolesCompleted = roles.isNotEmpty()
                    for (role in roles) {''',
    "track manual game completion",
)
view_model = replace_once(
    view_model,
    '''                            is CheckInResult.Unknown -> lines += "${nowLabel()} $label：结果未知（${result.message}）"''',
    '''                            is CheckInResult.Unknown -> {
                                allRolesCompleted = false
                                lines += "${nowLabel()} $label：结果未知（${result.message}）"
                            }''',
    "manual unknown is not definitive",
)
view_model = replace_once(
    view_model,
    '''                            is CheckInResult.Failure -> {
                                lines += "${nowLabel()} $label：失败（${result.message}）"''',
    '''                            is CheckInResult.Failure -> {
                                allRolesCompleted = false
                                lines += "${nowLabel()} $label：失败（${result.message}）"''',
    "manual failure is not complete",
)
view_model = replace_once(
    view_model,
    '''                        _state.value = _state.value.copy(output = lines.toList())
                    }
                }
            }
            DevLogger.info("任务", "签到测试结束，共 ${lines.size} 条结果", taskId)''',
    '''                        _state.value = _state.value.copy(output = lines.toList())
                    }
                    if (allRolesCompleted && !stopProvider) {
                        dailyCheckInProgressStore.markGameCompleted(game.id)
                        lines += "${nowLabel()} ${game.displayName}：已记录今日完成，计划任务不会重复执行"
                    }
                }
            }
            DevLogger.info("任务", "签到测试结束，共 ${lines.size} 条结果", taskId)''',
    "persist manual game completion",
)
write(view_model_path, view_model)

worker_path = "app/src/main/java/io/github/feigepro/checkintrace/AutoCheckInWorker.kt"
worker = read(worker_path)
worker = replace_once(
    worker,
    '''        val statusStore = AutoCheckInStatusStore(applicationContext)
        val previousSnapshot = statusStore.load()''',
    '''        val statusStore = AutoCheckInStatusStore(applicationContext)
        val dailyCheckInProgressStore = DailyCheckInProgressStore(applicationContext)
        val previousSnapshot = statusStore.load()''',
    "add worker daily progress store",
)
worker = replace_once(
    worker,
    '''            val games = GameCatalog.builtIn.filter { it.id in selected }
            val providers = mapOf(''',
    '''            val selectedGames = GameCatalog.builtIn.filter { it.id in selected }
            val completedGameIds = dailyCheckInProgressStore.loadToday().completedGameIds
            val games = selectedGames.filterNot { it.id in completedGameIds }
            selectedGames.filter { it.id in completedGameIds }.forEach { game ->
                lines += timestamped("${game.displayName}：今天已手动完成，计划任务跳过")
            }
            val providers = mapOf(''',
    "filter manually completed games",
)
worker = replace_once(
    worker,
    '''            DevLogger.info("自动任务", "每日签到开始，已选 ${games.size} 个游戏，第 $attempt 次尝试", taskId)
            if (games.isEmpty()) lines += timestamped("没有选择需要自动签到的游戏")''',
    '''            DevLogger.info(
                "自动任务",
                "每日签到开始，已选 ${selectedGames.size} 个游戏，待执行 ${games.size} 个，第 $attempt 次尝试",
                taskId,
            )
            if (selectedGames.isEmpty()) lines += timestamped("没有选择需要自动签到的游戏")
            if (selectedGames.isNotEmpty() && games.isEmpty()) {
                lines += timestamped("今天所选游戏均已完成，不访问平台，也不安排重试")
            }''',
    "successful no-op scheduled run",
)
write(worker_path, worker)

readme_path = "README.md"
readme = read(readme_path)
readme = replace_once(
    readme,
    '''- 米游社以二维码为主要登录方式，短信验证码为备用方式
- 米游社二维码采用已验证可用的电脑/通行证授权；取得凭证后，签到仍独立使用 Android 设备登记与 Android 请求头''',
    '''- 米游社使用已验证可用的电脑/通行证二维码登录；取得凭证后，签到独立使用 Android 设备登记与 Android 请求头''',
    "README QR-only login",
)
readme = readme.replace(
    "- 临时网络或接口错误会间隔约一小时自动重试一次\n",
    "- 临时网络或接口错误会间隔约一小时自动重试一次\n" +
    "- 当天手动签到成功后，计划任务会直接跳过对应游戏，不再重复请求或安排重试\n",
    1,
)
readme = readme.replace(
    "   - 米游社：优先点击“二维码登录”，使用米游社扫码并确认电脑/通行证授权；同一台手机可以先截图，再从米游社扫码页的相册中识别。短信验证码仍作为备用登录方式。",
    "   - 米游社：点击“二维码登录”，使用米游社扫码并确认电脑/通行证授权；同一台手机可以先截图，再从米游社扫码页的相册中识别。",
)
readme = readme.replace(
    "- 临时网络或接口错误最多自动重试一次；任务会持久化已完成、结果未知以及已开始提交的角色，避免重试时重复提交。",
    "- 当天手动签到确认成功后会记录游戏已完成；计划任务到点后直接跳过，不访问平台、不判定失败，也不安排重试。\n" +
    "- 临时网络或接口错误最多自动重试一次；任务会持久化已完成、结果未知以及已开始提交的角色，避免重试时重复提交。",
)
readme = regex_once(
    readme,
    r"- \*\*米游社备用短信登录\*\*：.*\n",
    "",
    "remove README SMS protocol",
)
write(readme_path, readme)

# Verify removal and scope.
for root in [Path("app/src/main"), Path("app/src/test"), Path("README.md")]:
    files = [root] if root.is_file() else [p for p in root.rglob("*") if p.is_file()]
    for file in files:
        if file.suffix not in {".kt", ".xml", ".md"}:
            continue
        content = file.read_text(encoding="utf-8")
        for removed in ["MihoyoCaptcha", "短信验证码", "AIGIS"]:
            if removed in content:
                raise RuntimeError(f"removed login reference {removed!r} remains in {file}")

# Remove temporary patch machinery and restore the ordinary CI definition before committing.
for temporary in [
    ".github/workflows/apply-manual-completion-fix.yml",
    ".github/manual-completion-trigger.txt",
    ".github/scripts/apply_manual_completion_fix.py",
]:
    Path(temporary).unlink(missing_ok=True)

write(
    ".github/workflows/android.yml",
    '''name: Android CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout
        uses: actions/checkout@v7

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Grant Gradle wrapper execute permission
        run: chmod +x ./gradlew

      - name: Test and build Debug and Release APKs
        shell: bash
        run: |
          set +e
          ./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain 2>&1 | tee build.log
          status=${PIPESTATUS[0]}
          exit "$status"

      - name: Upload build log
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: CheckinTrace-build-log
          path: build.log
          if-no-files-found: warn
          retention-days: 7

      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: CheckinTrace-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
''',
)
