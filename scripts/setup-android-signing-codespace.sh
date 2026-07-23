#!/usr/bin/env bash
set -euo pipefail

REPOSITORY="${GITHUB_REPOSITORY:-FeiGePro/CheckinTrace}"
KEY_ALIAS="checkintrace"
RELEASE_VERSION="v0.2.2"
ROOT_DIR="$(git rev-parse --show-toplevel)"
BACKUP_DIR="$ROOT_DIR/.signing-backup"
KEYSTORE_PATH="$BACKUP_DIR/CheckinTrace-release.jks"

run_gh() {
  env -u GITHUB_TOKEN -u GH_TOKEN gh "$@"
}

for command_name in git keytool base64 gh; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令：$command_name"
    exit 1
  fi
done

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
if ! grep -qxF '/.signing-backup/' "$ROOT_DIR/.git/info/exclude" 2>/dev/null; then
  printf '\n/.signing-backup/\n' >> "$ROOT_DIR/.git/info/exclude"
fi

if [[ -e "$KEYSTORE_PATH" ]]; then
  echo "签名文件已存在，为避免误覆盖已停止："
  echo "$KEYSTORE_PATH"
  exit 1
fi

echo "此脚本只在当前 GitHub Codespace 中生成签名文件。"
echo "密码不会写入仓库，也不会显示在终端。"
echo

if ! run_gh auth status --hostname github.com >/dev/null 2>&1; then
  echo "需要授权 GitHub CLI 管理仓库 Secrets。"
  echo "浏览器稍后会显示 GitHub 设备授权页面。"
  run_gh auth login \
    --hostname github.com \
    --git-protocol https \
    --web \
    --scopes repo,workflow
fi

read -r -s -p "设置长期签名密码：" STORE_PASSWORD
echo
read -r -s -p "再次输入确认：" STORE_PASSWORD_CONFIRM
echo

if [[ -z "$STORE_PASSWORD" ]]; then
  echo "密码不能为空。"
  exit 1
fi

if [[ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]]; then
  echo "两次输入的密码不一致。"
  exit 1
fi

if (( ${#STORE_PASSWORD} < 12 )); then
  echo "密码至少需要 12 个字符。"
  exit 1
fi

export CHECKINTRACE_SIGNING_PASSWORD="$STORE_PASSWORD"
keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_PATH" \
  -storetype JKS \
  -storepass:env CHECKINTRACE_SIGNING_PASSWORD \
  -keypass:env CHECKINTRACE_SIGNING_PASSWORD \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=FeiGePro, OU=CheckinTrace, O=FeiGePro, C=CN"

KEYSTORE_BASE64="$(base64 -w 0 "$KEYSTORE_PATH")"

printf '%s' "$KEYSTORE_BASE64" | run_gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPOSITORY"
printf '%s' "$STORE_PASSWORD" | run_gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPOSITORY"
printf '%s' "$KEY_ALIAS" | run_gh secret set ANDROID_KEY_ALIAS --repo "$REPOSITORY"
printf '%s' "$STORE_PASSWORD" | run_gh secret set ANDROID_KEY_PASSWORD --repo "$REPOSITORY"

unset CHECKINTRACE_SIGNING_PASSWORD
unset KEYSTORE_BASE64
STORE_PASSWORD=""
STORE_PASSWORD_CONFIRM=""

echo
echo "四个 Actions Secrets 已写入 $REPOSITORY："
run_gh secret list --repo "$REPOSITORY" | grep '^ANDROID_' || true

echo
echo "签名文件保存在："
echo "$KEYSTORE_PATH"
echo "请立即在 Codespaces 左侧文件列表中下载并永久备份该 .jks 文件。"
echo "同时把刚才输入的密码保存到密码管理器；GitHub 保存后不会再次显示它。"
echo

read -r -p "现在触发 $RELEASE_VERSION 正式发布吗？[y/N] " START_RELEASE
if [[ "$START_RELEASE" =~ ^[Yy]$ ]]; then
  run_gh workflow run release-android.yml \
    --repo "$REPOSITORY" \
    --ref main \
    -f version="$RELEASE_VERSION"
  echo "发布工作流已触发。查看状态："
  echo "https://github.com/$REPOSITORY/actions/workflows/release-android.yml"
else
  echo "未触发发布。稍后可在 GitHub Actions 中手动运行 Release Android APK。"
fi
