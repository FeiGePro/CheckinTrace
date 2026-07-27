package io.github.feigepro.checkintrace

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoAigisRequiredException
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoCaptchaLoginClient
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoQrLoginClient
import io.github.feigepro.checkintrace.security.CredentialRepository
import io.github.feigepro.checkintrace.security.EncryptedCredentialStore
import io.github.feigepro.checkintrace.ui.theme.SignInTheme
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MihoyoCaptchaLoginActivity : ComponentActivity() {
    private val repository by lazy { CredentialRepository(EncryptedCredentialStore(applicationContext)) }
    private val loginClient by lazy {
        val identity = repository.loadMihoyoDeviceIdentity()
            ?: MihoyoQrLoginClient.generateDeviceIdentity().also(repository::saveMihoyoDeviceIdentity)
        MihoyoCaptchaLoginClient(deviceIdentity = identity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SignInTheme {
                MihoyoCaptchaLoginScreen(
                    client = loginClient,
                    onCredentialReady = { credential ->
                        repository.saveMihoyo(DEFAULT_ACCOUNT, credential)
                        setResult(RESULT_OK)
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT = "default"
    }
}

private enum class PendingCaptchaAction { SEND, LOGIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MihoyoCaptchaLoginScreen(
    client: MihoyoCaptchaLoginClient,
    onCredentialReady: (io.github.feigepro.checkintrace.provider.mihoyo.MihoyoCredentialBundle) -> Unit,
    onClose: () -> Unit,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var actionType by rememberSaveable { mutableStateOf("") }
    var countdown by rememberSaveable { mutableIntStateOf(0) }
    var status by rememberSaveable { mutableStateOf("输入中国大陆手机号获取短信验证码") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var pendingAigis by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<PendingCaptchaAction?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun sendCaptcha(aigis: String = "") {
        busy = true
        status = if (aigis.isBlank()) "正在发送验证码……" else "安全验证已完成，正在重新发送验证码……"
        val result = client.sendCaptcha(phone, aigis)
        val error = result.exceptionOrNull()
        if (error is MihoyoAigisRequiredException) {
            busy = false
            pendingAction = PendingCaptchaAction.SEND
            pendingAigis = error.aigis
            status = "请完成米游社官方安全验证"
            return
        }
        if (error != null) {
            busy = false
            status = error.message ?: "发送验证码失败"
            return
        }
        val challenge = result.getOrThrow()
        actionType = challenge.actionType
        countdown = challenge.countdownSeconds.takeIf { it > 0 } ?: 60
        captcha = ""
        busy = false
        status = "验证码已发送，请查看短信"
    }

    suspend fun completeLogin(aigis: String = "") {
        busy = true
        status = if (aigis.isBlank()) "正在验证并交换签到凭证……" else "安全验证已完成，正在继续登录……"
        val result = client.loginByCaptcha(phone, captcha, actionType, aigis)
        val error = result.exceptionOrNull()
        if (error is MihoyoAigisRequiredException) {
            busy = false
            pendingAction = PendingCaptchaAction.LOGIN
            pendingAigis = error.aigis
            status = "请完成米游社官方安全验证"
            return
        }
        if (error != null) {
            busy = false
            status = error.message ?: "短信验证码登录失败"
            return
        }
        status = "登录成功，签到凭证已加密保存"
        busy = false
        onCredentialReady(result.getOrThrow())
    }

    LaunchedEffect(actionType) {
        while (actionType.isNotBlank() && countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("米游社短信登录") },
                navigationIcon = { TextButton(onClick = onClose) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android 米游社登录", fontWeight = FontWeight.Bold)
                    Text(
                        "手机号和区号会先使用米哈游通行证公钥加密。登录成功后仅在本机加密保存 stoken、cookie_token 与设备身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = phone,
                onValueChange = { value ->
                    phone = value.filter { it.isDigit() }.take(13)
                    if (actionType.isNotBlank()) {
                        actionType = ""
                        captcha = ""
                        countdown = 0
                        status = "手机号已改变，请重新发送验证码"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手机号") },
                placeholder = { Text("13800138000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                enabled = !busy && pendingAigis == null,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = captcha,
                    onValueChange = { captcha = it.filter(Char::isDigit).take(8) },
                    modifier = Modifier.weight(1f),
                    label = { Text("短信验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = actionType.isNotBlank() && !busy && pendingAigis == null,
                )
                OutlinedButton(
                    onClick = { scope.launch { sendCaptcha() } },
                    enabled = !busy && pendingAigis == null && countdown <= 0 && phone.isNotBlank(),
                ) {
                    Text(if (countdown > 0) "${countdown}s" else if (actionType.isBlank()) "发送" else "重发")
                }
            }

            Button(
                onClick = { scope.launch { completeLogin() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !busy && pendingAigis == null && actionType.isNotBlank() && captcha.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.padding(horizontal = 5.dp))
                }
                Text("登录并保存签到凭证")
            }

            Card(Modifier.fillMaxWidth()) {
                Text(
                    status,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            pendingAigis?.let { rawAigis ->
                AigisVerificationPanel(
                    rawAigis = rawAigis,
                    onSolved = { token ->
                        val action = pendingAction
                        pendingAigis = null
                        pendingAction = null
                        scope.launch {
                            when (action) {
                                PendingCaptchaAction.SEND -> sendCaptcha(token)
                                PendingCaptchaAction.LOGIN -> completeLogin(token)
                                null -> Unit
                            }
                        }
                    },
                    onCancel = {
                        pendingAigis = null
                        pendingAction = null
                        status = "安全验证已取消"
                    },
                    onFailure = {
                        pendingAigis = null
                        pendingAction = null
                        status = "安全验证失败：$it"
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AigisVerificationPanel(
    rawAigis: String,
    onSolved: (String) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val context = LocalContext.current
    val bridge = remember(rawAigis) { AigisJavascriptBridge(onSolved, onFailure) }
    var webView by remember(rawAigis) { mutableStateOf<WebView?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("官方安全验证", fontWeight = FontWeight.Bold)
                TextButton(onClick = onCancel) { Text("取消") }
            }
            AndroidView(
                factory = {
                    WebView(context).apply {
                        webView = this
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        addJavascriptInterface(bridge, "AndroidBridge")
                        loadDataWithBaseURL(
                            "https://user.miyoushe.com/",
                            buildAigisHtml(rawAigis),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
        }
    }

    DisposableEffect(rawAigis) {
        onDispose {
            webView?.removeJavascriptInterface("AndroidBridge")
            webView?.destroy()
        }
    }
}

private class AigisJavascriptBridge(
    private val onSolved: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun solved(value: String) {
        mainHandler.post { onSolved(value) }
    }

    @JavascriptInterface
    fun failed(message: String) {
        mainHandler.post { onFailure(message.ifBlank { "未知错误" }) }
    }
}

private fun buildAigisHtml(rawAigis: String): String {
    val encoded = Base64.getEncoder().encodeToString(rawAigis.toByteArray(Charsets.UTF_8))
    return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
          <script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
          <script src="https://static.geetest.com/v4/gt4.js"></script>
          <style>
            html,body { margin:0; padding:0; background:transparent; font-family:sans-serif; }
            #box { min-height:320px; display:flex; align-items:center; justify-content:center; }
            #message { padding:16px; color:#666; text-align:center; }
          </style>
        </head>
        <body>
          <div id="box"><div id="message">正在加载米游社安全验证……</div></div>
          <script>
            const decodeUtf8 = value => decodeURIComponent(escape(atob(value)));
            const aigis = JSON.parse(decodeUtf8("$encoded"));
            const data = typeof aigis.data === "string" ? JSON.parse(aigis.data) : (aigis.data || {});
            const fail = message => AndroidBridge.failed(String(message || "安全验证失败"));
            const finish = validate => {
              if (!validate) { fail("安全验证未返回结果"); return; }
              const json = JSON.stringify(validate);
              const payload = btoa(unescape(encodeURIComponent(json)));
              AndroidBridge.solved(String(aigis.session_id || "") + ";" + payload);
            };
            window.addEventListener("load", () => {
              document.getElementById("message").remove();
              try {
                if (Object.prototype.hasOwnProperty.call(data, "challenge")) {
                  if (typeof window.initGeetest !== "function") { fail("极验 v3 脚本加载失败"); return; }
                  window.initGeetest({
                    gt: data.gt,
                    challenge: data.challenge,
                    offline: false,
                    new_captcha: true,
                    product: "popup",
                    width: "100%",
                    https: true
                  }, captcha => {
                    captcha.onReady(() => captcha.verify());
                    captcha.onSuccess(() => finish(captcha.getValidate()));
                    captcha.onError(error => fail(JSON.stringify(error)));
                    captcha.onClose(() => fail("安全验证已取消"));
                  });
                  return;
                }
                if (typeof window.initGeetest4 !== "function") { fail("极验 v4 脚本加载失败"); return; }
                window.initGeetest4({
                  captchaId: data.gt,
                  riskType: data.risk_type,
                  product: "popup",
                  nextWidth: "300px",
                  lang: "zho",
                  userInfo: JSON.stringify({ session_id: aigis.session_id }),
                  https: true,
                  protocol: "https"
                }, captcha => {
                  captcha.onReady(() => captcha.showCaptcha());
                  captcha.onSuccess(() => finish(captcha.getValidate()));
                  captcha.onError(error => fail(JSON.stringify(error)));
                  captcha.onClose(() => fail("安全验证已取消"));
                });
              } catch (error) {
                fail(error && error.message ? error.message : String(error));
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
