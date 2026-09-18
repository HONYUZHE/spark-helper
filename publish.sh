#!/bin/bash
# ---------------------------------------------------------------------------
# 一键开源发布：建仓库 → 推代码 → 建 Release → 上传 APK
#
#   GH_TOKEN=ghp_xxx ./publish.sh
#   # 或先把 token 写进文件（推荐，免得进 shell 历史）：
#   printf '%s' 'ghp_xxx' > /root/.dsh/.github-token && chmod 600 /root/.dsh/.github-token
#   ./publish.sh
#
# token 需要 classic PAT 的 repo 权限，或 fine-grained 的
# "Administration: write" + "Contents: write"。
# 发完请到 https://github.com/settings/tokens 把 token Revoke 掉。
# ---------------------------------------------------------------------------
set -euo pipefail

OWNER=${OWNER:-HONYUZHE}
REPO=${REPO:-spark-helper}
TAG=${TAG:-v1.0.4}
BRANCH=main
TOKEN_FILE=${TOKEN_FILE:-/root/.dsh/.github-token}
API=https://api.github.com
UPLOADS=https://uploads.github.com
UA=spark-helper-publish

BASE="$(cd "$(dirname "$0")" && pwd)"
APK="$BASE/out/spark-helper-$TAG.apk"
[ -f "$APK" ] || APK="$BASE/out/$(ls "$BASE/out" 2>/dev/null | grep '\.apk$' | head -1)"

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "✗ $*" >&2; exit 1; }

TOKEN=${GH_TOKEN:-${GITHUB_TOKEN:-}}
if [ -z "$TOKEN" ] && [ -f "$TOKEN_FILE" ]; then
  TOKEN=$(tr -d ' \t\r\n' < "$TOKEN_FILE")
  say "· 使用 $TOKEN_FILE 里的 token"
fi
[ -n "$TOKEN" ] || die "没找到 token（写进 $TOKEN_FILE 或设 GH_TOKEN）"
[ -f "$APK" ] || die "没找到 APK：$APK（先跑 ./build.sh）"

cd "$BASE"
git rev-parse --git-dir >/dev/null 2>&1 || die "当前目录不是 git 仓库"
[ -z "$(git status --porcelain)" ] || { git status --short; die "工作区有未提交改动，先 commit"; }

DESC="定时自动给抖音好友发一条消息，维持聊天火花。无障碍实现，不需要 root，不联网。"

# ---------- 1. 仓库 ----------
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  -H "User-Agent: $UA" "$API/repos/$OWNER/$REPO")
case "$code" in
  200) say "· 仓库已存在：https://github.com/$OWNER/$REPO" ;;
  404)
    say "· 创建仓库 $OWNER/$REPO …"
    body=$(curl -s -X POST "$API/user/repos" -H "Authorization: Bearer $TOKEN" \
      -H "User-Agent: $UA" -H 'Accept: application/vnd.github+json' \
      -d "{\"name\":\"$REPO\",\"description\":\"$DESC\",\"private\":false,\"has_issues\":true,\"has_wiki\":false}" \
      -w '\n%{http_code}')
    sc=$(printf '%s' "$body" | tail -n1)
    [ "$sc" = "201" ] || { printf '%s\n' "$body" >&2; die "建仓库失败（HTTP $sc）"; }
    say "· 已创建"
    ;;
  401) die "token 无效或已过期（401）" ;;
  403) die "token 权限不足（403）：classic PAT 要勾 repo" ;;
  *)   die "查询仓库失败（HTTP $code）" ;;
esac

# ---------- 2. 推代码 ----------
git remote get-url origin >/dev/null 2>&1 \
  && git remote set-url origin "https://github.com/$OWNER/$REPO.git" \
  || git remote add origin "https://github.com/$OWNER/$REPO.git"
AUTH=$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')
say "· 推送 $BRANCH …"
git -c credential.helper= -c http.extraHeader="Authorization: Basic $AUTH" push -u origin "$BRANCH"
unset AUTH

# ---------- 3. Release ----------
say "· 检查 Release $TAG …"
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  -H "User-Agent: $UA" "$API/repos/$OWNER/$REPO/releases/tags/$TAG")

BODY=$(cat <<'EOF'
## 下载

点下面的 **Assets → spark-helper-vX.Y.Z.apk**（约 54 KB，Android 6.0+），传到手机点安装。

装完请先看 [使用说明](https://github.com/OWNER/REPO/blob/main/使用说明.md)：

1. 打开 App → 点顶部灰色状态区 → **开启无障碍服务**（必做）
2. **关掉电池优化**、确认 **常驻通知保活** 勾着
3. 「＋ 添加任务」：填对方在抖音消息列表里显示的名字 + 消息内容 + 时间
4. **先点「试运行」**（会走完整流程但**不点发送**），确认没问题再等定时

> ⚠️ 抖音的用户协议不允许自动化操作。本项目只在你自己的手机上、给你自己的好友发消息，
> 请保持低频（一天一条），账号风险自负。
EOF
)
BODY=${BODY//OWNER/$OWNER}
BODY=${BODY//REPO/$REPO}

if [ "$code" = "200" ]; then
  say "· Release 已存在，跳过创建"
else
  payload=$(python3 - "$TAG" "$BRANCH" "$BODY" <<'PY'
import json,sys
print(json.dumps({"tag_name":sys.argv[1],"target_commitish":sys.argv[2],
                  "name":"火花助手 "+sys.argv[1],"body":sys.argv[3],
                  "draft":False,"prerelease":False}))
PY
)
  resp=$(curl -s -X POST "$API/repos/$OWNER/$REPO/releases" \
    -H "Authorization: Bearer $TOKEN" -H "User-Agent: $UA" \
    -H 'Accept: application/vnd.github+json' -d "$payload")
  say "· 已创建 Release $TAG"
fi

RID=$(curl -s -H "Authorization: Bearer $TOKEN" -H "User-Agent: $UA" \
  "$API/repos/$OWNER/$REPO/releases/tags/$TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

# ---------- 4. 上传 APK ----------
SIZE=$(stat -c%s "$APK")
say "· 上传 APK（$SIZE 字节）…"
UP=$(curl -s -X POST "$UPLOADS/repos/$OWNER/$REPO/releases/$RID/assets?name=spark-helper-$TAG.apk" \
  -H "Authorization: Bearer $TOKEN" -H "User-Agent: $UA" \
  -H 'Content-Type: application/vnd.android.package-archive' \
  --data-binary "@$APK" -w '\n%{http_code}')
sc=$(printf '%s' "$UP" | tail -n1)
case "$sc" in
  201) say "· 上传成功" ;;
  422) say "· 同名资源已存在，跳过（说明之前传过）" ;;
  *)   printf '%s\n' "$UP" >&2; die "上传失败（HTTP $sc）" ;;
esac

say ""
say "✓ 完成："
say "  仓库    https://github.com/$OWNER/$REPO"
say "  Release https://github.com/$OWNER/$REPO/releases/tag/$TAG"
say ""
say "  收尾建议："
say "    1. 到 https://github.com/settings/tokens 把这个 token Revoke 掉"
say "    2. rm -f $TOKEN_FILE"
