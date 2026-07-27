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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoAigisRequiredException
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoCaptchaLoginClient
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoCredentialBundle
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
    onCredentialReady: (MihoyoCredentialBundle) -> Unit,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
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
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
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
        }
    }

    pendingAigis?.let { rawAigis ->
        AigisVerificationDialog(
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AigisVerificationDialog(
    rawAigis: String,
    onSolved: (String) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val context = LocalContext.current
    val bridge = remember(rawAigis) { AigisJavascriptBridge(onSolved, onFailure) }
    var webView by remember(rawAigis) { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("米游社官方安全验证", fontWeight = FontWeight.Bold)
                        Text(
                            "验证控件由极验官方服务加载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCancel) { Text("取消") }
                }

                AndroidView(
                    factory = {
                        WebView(context).apply {
                            webView = this
                            setBackgroundColor(Color.WHITE)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.userAgentString = "${settings.userAgentString} miHoYoBBS/2.106.2"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        bridge.failed(error?.description?.toString().orEmpty())
                                    }
                                }
                            }
                            addJavascriptInterface(bridge, "AndroidBridge")
                            loadDataWithBaseURL(
                                "https://user.miyoushe.com/",
                                buildMihoyoAigisHtml(rawAigis),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    DisposableEffect(rawAigis) {
        onDispose {
            webView?.stopLoading()
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

internal fun buildMihoyoAigisHtml(rawAigis: String): String {
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
            html, body {
              width: 100%;
              height: 100%;
              margin: 0;
              padding: 0;
              overflow: hidden;
              background: #ffffff;
              font-family: sans-serif;
            }
            #box {
              width: 100%;
              height: 100%;
              min-height: 360px;
              display: flex;
              align-items: center;
              justify-content: center;
              box-sizing: border-box;
              padding: 12px;
            }
            #message {
              color: #666;
              text-align: center;
              line-height: 1.6;
            }
          </style>
        </head>
        <body>
          <div id="box"><div id="message">正在加载米游社安全验证……</div></div>
          <script>
            const decodeUtf8 = value => decodeURIComponent(escape(atob(value)));
            const aigis = JSON.parse(decodeUtf8("$encoded"));
            const data = typeof aigis.data === "string" ? JSON.parse(aigis.data) : (aigis.data || {});
            let completed = false;

            const fail = message => {
              if (completed) return;
              completed = true;
              AndroidBridge.failed(String(message || "安全验证失败"));
            };

            const finish = validate => {
              if (completed) return;
              if (!validate) {
                fail("安全验证未返回结果");
                return;
              }
              completed = true;
              const json = JSON.stringify(validate);
              const payload = btoa(unescape(encodeURIComponent(json)));
              AndroidBridge.solved(String(aigis.session_id || "") + ";" + payload);
            };

            window.onerror = (message, source, line, column, error) => {
              fail(error && error.message ? error.message : String(message));
              return true;
            };

            window.addEventListener("unhandledrejection", event => {
              const reason = event && event.reason;
              fail(reason && reason.message ? reason.message : String(reason || "安全验证脚本执行失败"));
            });

            window.addEventListener("load", () => {
              const message = document.getElementById("message");
              if (message) message.remove();

              try {
                if (Object.prototype.hasOwnProperty.call(data, "challenge")) {
                  if (typeof window.initGeetest !== "function") {
                    fail("极验 v3 脚本加载失败");
                    return;
                  }
                  window.initGeetest({
                    gt: data.gt,
                    challenge: data.challenge,
                    offline: false,
                    new_captcha: true,
                    product: "custom",
                    area: "#box",
                    width: "250px",
                    https: true
                  }, captcha => {
                    captcha.appendTo("#box");
                    captcha.onSuccess(() => finish(captcha.getValidate()));
                    captcha.onError(error => fail(JSON.stringify(error)));
                    captcha.onClose(() => {
                      const validate = captcha.getValidate();
                      if (validate) finish(validate);
                      else fail("安全验证已取消");
                    });
                  });
                  return;
                }

                if (typeof window.initGeetest4 !== "function") {
                  fail("极验 v4 脚本加载失败");
                  return;
                }
                window.initGeetest4({
                  captchaId: data.gt,
                  riskType: data.risk_type,
                  product: "popup",
                  nextWidth: "250px",
                  lang: "zho",
                  userInfo: JSON.stringify({ session_id: aigis.session_id }),
                  https: true,
                  protocol: "https"
                }, captcha => {
                  captcha.appendTo("#box");
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
