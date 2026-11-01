# 乐仔学英语 (lezai-english-tv)

局域网 Android TV 应用：**把英语启蒙"嵌进动画片"** —— 播乐仔爱看的**火车动画**，播到单词卡的时刻自动**暂停 → 播单词发音 → 出 A/B/C 听音选图 → 选对继续播**。给 4 岁的乐仔玩。

> `200_工具中心/214_小米电视工具/apps/` 下的第二个应用（第一个是 `lezai-player` 照片轮播）。
> 独立 GitHub 仓库 `oliver7l/lezai-english-tv`，GitHub Actions 构建 APK（本机无 JDK/SDK）。

## 它和旧「小火车英语课堂」的关系（重点）

旧工程（`196_归档中心/资料库/13_KidsEnglish/`）已经把**单词卡（emoji+英+中+例句）+ TTS 发音烧进 Chuggington 火车动画**，做成被动视频。本应用**不重做视频**，而是把它**变成交互**：

- **直接播**旧成片 `小火车英语课堂-EP1.mp4`（324MB，20 分钟，8 张单词卡烧在画面里）。
- 播放到每张卡**刚消失**的时刻（卡在 `t … t+8s`，故触发在 `t+9s`）→ **暂停** → 播该词标准发音 → 出不透明 **A/B/C**（图片选项，盖住画面里残留的卡，防剧透）→ 答对（或连错 2 次亮答案）→ **继续播**。
- 8 个词与触发点完全沿用旧的 `build_video.py::WORDS`：train@84s / tunnel@224s / bridge@364s / wheels@504s / whistle@644s / fast@784s / stop@924s / friend@1064s。

⇒ 既保留原动画，又加入他最近喜欢的"选 ABC"主动答题。**不用任何 Chuggington 画面/角色去发布**，仅家庭自用 TV。

## 架构

```
mini96(192.168.1.96)                                 电视(Android)
┌──────────────────────────────────┐   HTTP       ┌─────────────────────┐
│ tools/serve_english.py (8485)    │◄────────────►│ LezaiEnglish.apk    │
│  GET /quiz.json  (video_file+题) │  LAN         │  VideoView 播 ep1    │
│  GET /video/ep1.mp4 (Range 206)  │              │  到点暂停→ABC→继续    │
│  GET /audio/<slug>.m4a           │              │                     │
└──────────────────────────────────┘              └─────────────────────┘
        ▲ 内容由 tools/make_english_content.py 生成（macOS say+afconvert）
        ▲ 视频只读自 196_归档中心/资料库/13_KidsEnglish/01_成品/（不出盘）
```

- App：Kotlin，零第三方依赖（`VideoView`/`MediaPlayer` + `HttpURLConnection` + `org.json`）
- 视频：`/video/<alias>`，**支持 HTTP Range**（206），MediaPlayer 才能流式播放/拖动；ASCII 别名 `ep1.mp4` → 真实中文文件名由服务端别名表映射
- 发音：`/audio/<slug>.m4a`（自己用 `say` 生成，不依赖视频里的音轨做提示）

## 数据契约（quiz.json，视频锚定）

```json
{
  "deck": "lezai-chuggington-ep1",
  "video_file": "ep1.mp4",
  "video_title": "小火车英语课堂 EP1",
  "quizzes": [
    {
      "at_ms": 84000,
      "target_audio": "train",
      "target_word": "火车",
      "options": [
        { "slug": "apple", "emoji": "🍎", "zh": "苹果", "label": "A" },
        { "slug": "train", "emoji": "🚂", "zh": "火车", "label": "B" },
        { "slug": "cat",   "emoji": "🐱", "zh": "猫",   "label": "C" }
      ],
      "answer": 1
    }
  ]
}
```

App 轮询 `VideoView.currentPosition`，越过某题 `at_ms` 就暂停并显示该题。选项**不发音**（否则听力题泄答案）；题目一出现自动播 `target_audio` 的 m4a。选项 emoji 大图，实测这台 Redmi TV 彩色 emoji 正常。

## 玩法（遥控器）

| 状态 | 键 | 作用 |
|---|---|---|
| 播放中 | 返回 | 退出 |
| 答题 | ← / → | 在 A/B/C 间移焦点（高亮） |
| 答题 | ↑ | 重播当前单词 |
| 答题 | OK / 确认 | 选定 |
| 看完 | OK | 重看一遍；返回=退出 |

答对 → 再播该词 + ⭐+1 → 继续播；答错 → 温和提示音，连错 2 次亮出正确词后继续（不卡死、不批评）。

## 跑起来（顺序很重要）

```bash
# 在 200_工具中心/214_小米电视工具/ 模块根目录：
./run.sh english        # ① 生成 content/quiz.json + content/audio/*.m4a
./run.sh serve-eng      # ② 起内容服务（8485，另开终端保持运行）→ /video/ep1.mp4 + Range 就绪
./run.sh tv connect
<module>/tools/bin/adb uninstall com.obc.tv.lezaienglish   # 防 debug 签名冲突
./run.sh tv install <下载的>.apk
./run.sh tv shell am start -n com.obc.tv.lezaienglish/.MainActivity --es server http://192.168.1.96:8485
```

## 边界

- **视频锚定**：题目时间点绑定在 EP1 的时间轴上；换别的动画要改 `make_english_content.py::WORD_TIMES`（并更新视频别名/文件）。
- 视频是家庭自用素材，只在本机局域网只读分发，不出公网、不对外发布（版权）。
- 一次播 20 分钟偏长；后续可只用它的 3 分钟切片（`01_成品/EP1-三分钟版/`）按词一段一段来。
- 单词/发音自制（`make_english_content.py`），不依赖视频原声轨做提示。
