package io.github.feigepro.checkintrace

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.feigepro.checkintrace.logging.DevLogger
import io.github.feigepro.checkintrace.provider.skland.SklandApi
import io.github.feigepro.checkintrace.provider.skland.SklandQrLoginClient
import io.github.feigepro.checkintrace.provider.skland.SklandQrPollResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SklandLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = SklandQrLoginClient()
    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var refreshButton: Button
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "森空岛扫码登录"
        status = TextView(this).apply {
            text = "正在创建森空岛官方登录二维码……"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 24)
        }
        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(24, 16, 24, 16)
        }
        refreshButton = Button(this).apply {
            text = "重新生成二维码"
            setOnClickListener { startLogin() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(qrImage, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(refreshButton, LinearLayout.LayoutParams(-1, -2))
        })
        startLogin()
    }

    private fun startLogin() {
        loginJob?.cancel()
        val taskId = DevLogger.newTaskId()
        loginJob = scope.launch {
            refreshButton.isEnabled = false
            status.text = "正在创建森空岛官方登录二维码……"
            qrImage.setImageDrawable(null)
            val session = client.create(taskId).getOrElse {
                showFailure("二维码创建失败：${it.message}")
                return@launch
            }
            qrImage.setImageBitmap(makeQr(session.qrContent))
            status.text = "请用森空岛 App 扫码并确认登录\n同一台手机可截图后从扫码页相册识别"
            refreshButton.isEnabled = true
            repeat(50) {
                delay(2_000)
                if (!isActive) return@launch
                when (val result = client.poll(session, taskId).getOrElse {
                    showFailure("登录轮询失败：${it.message}")
                    return@launch
                }) {
                    SklandQrPollResult.Waiting -> Unit
                    is SklandQrPollResult.Confirmed -> {
                        status.text = "已确认，正在验证森空岛凭证……"
                        SklandApi().exchangeToken(result.token, taskId).getOrElse {
                            showFailure("登录凭证验证失败：${it.message}")
                            return@launch
                        }
                        DevLogger.info("森空岛/登录", "登录成功", taskId)
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, result.token))
                        finish()
                        return@launch
                    }
                }
            }
            showFailure("二维码已过期，请重新生成")
        }
    }

    private fun showFailure(message: String) {
        status.text = message
        refreshButton.isEnabled = true
    }

    private fun makeQr(content: String): Bitmap {
        val size = (resources.displayMetrics.density * 280).toInt().coerceAtLeast(640)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val EXTRA_TOKEN = "skland_token" }
}
