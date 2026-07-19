# 签迹 CheckinTrace

一个在 Android 本地运行的游戏社区每日签到工具。目前支持米游社与森空岛，签到时间和游戏均可自行选择。

> 项目仍处于测试阶段。第三方平台的接口、登录流程和风控策略可能随时变化，请只在自己的账号上谨慎使用。

## 功能

- 米游社扫码登录，支持原神、崩坏：星穹铁道、绝区零、崩坏 3、未定事件簿和崩坏学园 2
- 森空岛网页登录，支持明日方舟和明日方舟：终末地
- 自由选择参与签到的游戏，未选择的游戏默认收起
- 自定义每日执行时间，也可随时手动签到
- 使用 WorkManager 在联网且电量不低时执行低功耗后台任务
- 登录凭证通过 Android Keystore 与 AES-GCM 加密，仅保存在本机
- 区分成功、今日已签到、凭证失效和人工验证等状态
- 检测到人工验证后停止该平台的后续请求，不尝试破解或绕过验证
- Debug 构建提供脱敏开发日志，Release 构建不记录开发日志

## 使用

1. 安装 APK 并打开“签迹”。
2. 分别登录需要使用的社区。米游社二维码可以截图后，在米游社“扫一扫”中从相册识别；森空岛登录在应用内官方网页完成。
3. 在“我的签到”中添加游戏，点击顶部时间即可调整每日计划。
4. 首次使用建议点击“立即签到”，确认账号、角色与返回状态均正常。
5. 保留应用的后台运行权限。Android 的省电策略可能让计划任务比设定时间稍晚执行。

自 0.2.1 起，应用包名为 `io.github.feigepro.checkintrace`。它与早期测试包是两个独立应用，旧版凭证不会迁移；确认新版本工作正常后，可手动卸载旧版。后续覆盖安装仍必须保持相同包名和签名。公开发布不会提交任何签名私钥。

## 构建

环境要求：JDK 17、Android SDK 35。

Windows：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS / Linux：

```bash
./gradlew testDebugUnitTest assembleDebug
```

生成的 Debug APK 位于 `app/build/outputs/apk/debug/`。正式发布应使用自己的长期签名密钥，并通过 GitHub Secrets 或本机私密配置注入；不要把密钥、密码、Token、Cookie 或用户日志提交到仓库。

## 安全与隐私

- 凭证不会上传到自建服务器；网络请求直接发往对应平台
- 日志中的常见 Token、Cookie、账号标识和设备标识会被脱敏
- Release 构建关闭开发日志
- 请勿在 Issue 中粘贴二维码内容、Token、Cookie、完整请求头或未经检查的日志

详细报告方式见 [SECURITY.md](SECURITY.md)。

## 兼容性参考

本项目的 Android 代码为独立 Kotlin 实现。协议行为核对参考了以下公开项目：

- [nonebot-plugin-mystool](https://github.com/Ljzd-PRO/nonebot-plugin-mystool)
- [mihoyo_qr_login](https://github.com/jiarui666/mihoyo_qr_login)
- [MiyoQian](https://github.com/Marchen-orz/MiyoQian)
- [nonebot-plugin-skland](https://github.com/FrostN0v0/nonebot-plugin-skland)
- [skyland_auto_checkin](https://github.com/devnakx/skyland_auto_checkin)

感谢这些项目公开的研究与实现。第三方项目仍分别受其原始许可证和使用条件约束。

## 免责声明

本项目与米哈游、米游社、鹰角网络、森空岛及相关游戏官方无关，也未获得其认可。项目仅供学习和个人使用，不保证接口长期可用。使用者应自行遵守平台协议并承担账号、数据及服务变更风险。

## 许可证

[MIT License](LICENSE)
