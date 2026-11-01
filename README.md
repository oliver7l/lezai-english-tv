# 乐仔学英语 (lezai-english-tv)

局域网 Android TV 应用：**听音选图 A/B/C** 英语启蒙小游戏，火车主题为主、常见词陪跑。给 4 岁的乐仔玩。

> 这是 `200_工具中心/214_小米电视工具/apps/` 下的第二个应用（第一个是 `lezai-player` 照片轮播）。
> 独立 GitHub 仓库 `oliver7l/lezai-english-tv`，用 GitHub Actions 构建 APK（本机无 JDK/SDK）。

## 它和旧的「小火车英语课堂」有什么不同

旧工程（`196_归档中心/资料库/13_KidsEnglish/`）是**被动看视频**：把单词卡烧进 Chuggington 正片 → 因此受版权限制（只能家庭自用、不能外发）。

本应用换打法：
- **主动答题**：听单词发音 → 从 A/B/C 三张图里选出听到的那个（练的是 4 岁该练的**听力辨认**，不需要认字）。
- **不用任何 Chuggington 画面/角色**：只用通用英文单词 + emoji + 自制 TTS 发音 ⇒ **不碰那条版权红线**。
- 词表沿用旧「小火车」那套火车词（train/tunnel/bridge/wheels…），另配乐仔熟的常见词当干扰项。

## 架构

```
mini96(192.168.1.96)                          电视(Android)
┌──────────────────────────┐   HTTP   ┌──────────────────────┐
│ tools/serve_english.py   │◄────────►│ LezaiEnglish.apk     │
│  GET /quiz.json          │   LAN    │  拉题→播单词音        │
│  GET /audio/<slug>.m4a   │          │  ←→选 ABC · OK 确认  │
└──────────────────────────┘          └──────────────────────┘
        ▲
        │ 内容由此生成
tools/make_english_content.py（macOS say + afconvert，零依赖）
```

- 服务端：`200_工具中心/214_小米电视工具/tools/serve_english.py`（Python 标准库，端口 **8485**，与照片服务 8484 错开）
- 内容：`tools/make_english_content.py`（词表内置为唯一事实源 → 生成 `content/quiz.json` + `content/audio/*.m4a`）
- App：本仓库，Kotlin，零第三方依赖（`HttpURLConnection` + `org.json` + `MediaPlayer`）

## 玩法（遥控器）

| 键 | 作用 |
|---|---|
| 自动 | 每题自动播目标词发音 |
| ↑ | 重播当前单词 |
| ← / → | 在 A/B/C 之间移动焦点（高亮） |
| OK / 确认 | 选定当前高亮的图 |
| 返回 | 退出 |

对 → 再播一遍该词 + ⭐+1 + 自动下一题；错 → 温和提示音（不批评），连错 2 次亮出正确词后再进。一轮结束显示「完成啦 ⭐×N」，按 OK 再来一轮。

## 数据契约（quiz.json）

```json
{
  "deck": "lezai-train-v1",
  "questions": [
    {
      "id": 1,
      "target_audio": "train",
      "target_word": "火车",
      "options": [
        { "slug": "train", "emoji": "🚂", "zh": "火车", "label": "A" },
        { "slug": "apple", "emoji": "🍎", "zh": "苹果", "label": "B" },
        { "slug": "cat",   "emoji": "🐱", "zh": "猫",   "label": "C" }
      ],
      "answer": 0
    }
  ]
}
```

App 只读 `questions[].{target_audio, options[].{emoji,label,zh}, answer}`。选项**不发音**（否则听力题泄答案）。音频走 `/audio/<slug>.m4a`，另需 `/audio/retry.m4a`（错的提示音）。

## 构建（GitHub Actions，无需本地 SDK）

推 `main` 即自动构建；或 Actions 页 `workflow_dispatch`。
- JDK 17 + Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24
- 产物：`build/outputs/apk/debug/app-debug.apk`（Artifact 名 `lezai-english-debug`）
- 打 `v*` tag 会把 APK 附到 Release

## 跑起来（前置顺序很重要）

```bash
# 在 200_工具中心/214_小米电视工具/ 模块根目录：
./run.sh english        # ① 生成内容（quiz.json + audio/*.m4a）
./run.sh serve-eng      # ② 起英语内容服务（8485，另开一个终端窗口，保持运行）

# ③ CI 出 APK 后装到电视（见模块 README §三之二 adb 前置）：
./run.sh tv connect
<module>/tools/bin/adb uninstall com.obc.tv.lezaienglish   # 防 debug 签名冲突
./run.sh tv install <下载下来的>.apk
./run.sh tv shell am start -n com.obc.tv.lezaienglish/.MainActivity --es server http://192.168.1.96:8485
```

## emoji 渲染注意

选项用大 emoji 画。Android TV 一般自带彩色 emoji 字体；**若这台 Redmi 电视不显示彩色 emoji（变方块/缺字）**，改走 PNG 卡回退：`make_english_content.py` 可加 `--cards` 生成 `content/img/<slug>.png`（旧「小火车」的 PIL 画法），App 端选项改用 `ImageView` 读 `/img/<slug>.png`（复用 lezai-player 已验证的图片通道）。装机验证时先看一眼。

## 边界

- v1 只做「听音选图」一种玩法（不认字、不跟读评分）。
- 内容只在局域网内，App 不联网、不发消息、不写盘。
- 单词/发音全部自制、无第三方音视频 ⇒ 干净，不受旧视频版权限制。
