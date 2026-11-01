package com.obc.tv.lezaienglish

import android.app.Activity
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 乐仔学英语 · 动画内嵌 ABC 听音选图。
 *
 * 不另做视频：直接播用户已有的《小火车英语课堂》EP1（Chuggington 英文火车动画，
 * 单词卡已烧在画面里）。播放到每张单词卡刚消失的时刻 → **暂停 → 播该词标准发音 →
 * 出不透明 A/B/C（图片选项，盖住画面防剧透）→ 选对继续播**。
 *
 * 数据（tools/serve_english.py，端口 8485）：
 *   GET /quiz.json → { video_file, quizzes:[ {at_ms,target_audio,options[{slug,emoji,zh,label}],answer} ] }
 *   GET /video/<video_file>   动画片（支持 HTTP Range）
 *   GET /audio/<slug>.m4a     单词发音；retry.m4a 错的提示音
 *
 * 遥控器：播放中 返回=退出；答题时 ←/→ 移焦点、↑ 重播发音、OK=选定；答完 自动继续。
 * 服务器地址：Intent extra "server" > SharedPreferences > 默认 192.168.1.96:8485。
 *
 * 零第三方依赖：VideoView/MediaPlayer + HttpURLConnection + org.json 都在 framework 里。
 */
class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_SERVER = "http://192.168.1.96:8485"
        private const val PREFS = "english_prefs"
        private const val KEY_SERVER = "server_url"
        private const val POLL_MS = 300L
        private const val NEXT_DELAY_MS = 1500L
        private const val REVEAL_DELAY_MS = 2600L
        private const val FLASH_MS = 450L
        private const val MAX_WRONG = 2
        private const val BG_NORMAL = 0x22FFFFFF.toInt()
        private const val BG_FOCUS = 0xFF1F6FEB.toInt()
        private const val BG_CORRECT = 0xFF2EA043.toInt()
        private const val BG_WRONG = 0xFF9E6A00.toInt()
    }

    private lateinit var video: VideoView
    private lateinit var quizLayer: LinearLayout
    private lateinit var headerView: TextView
    private lateinit var promptView: TextView
    private lateinit var statusView: TextView
    private lateinit var containers: List<LinearLayout>
    private lateinit var labelViews: List<TextView>
    private lateinit var emojiViews: List<TextView>

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var sfx: MediaPlayer? = null

    private var server: String = DEFAULT_SERVER
    private var videoFile: String = "ep1.mp4"
    private var quizzes: JSONArray? = null
    private var nextQuiz = 0          // 下一道该触发的题（按时间顺序）
    private var curQuiz = -1          // 正在答的题下标；-1 = 不在答题
    private var stars = 0
    private var focusedOpt = 0
    private var wrongOnCurrent = 0
    private var finished = false
    private var locked = false

    private val tick = object : Runnable {
        override fun run() {
            maybeQuiz()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        video = findViewById(R.id.video)
        quizLayer = findViewById(R.id.quizLayer)
        headerView = findViewById(R.id.header)
        promptView = findViewById(R.id.prompt)
        statusView = findViewById(R.id.status)
        containers = listOf(findViewById(R.id.opt0), findViewById(R.id.opt1), findViewById(R.id.opt2))
        labelViews = listOf(findViewById(R.id.lbl0), findViewById(R.id.lbl1), findViewById(R.id.lbl2))
        emojiViews = listOf(findViewById(R.id.emo0), findViewById(R.id.emo1), findViewById(R.id.emo2))

        quizLayer.visibility = View.GONE
        statusView.visibility = View.GONE
        video.setMediaController(null)
        video.setOnErrorListener { _, what, extra ->
            status("动画播放失败 ($what / $extra)\n$server/video/$videoFile")
            true
        }
        video.setOnCompletionListener { showDone() }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        server = intent?.getStringExtra("server")
            ?: prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        server = server.trimEnd('/')

        loadQuiz()
    }

    /** 拉 quiz.json（视频文件 + 时间点题目），成功则开始播视频。 */
    private fun loadQuiz() {
        status("正在连接 $server …")
        pool.execute {
            try {
                val json = httpGetText("$server/quiz.json")
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("quizzes")
                val file = obj.optString("video_file", "ep1.mp4")
                runOnUiThread {
                    if (arr.length() == 0) {
                        status("服务器上没题目（$server/quiz.json）")
                        return@runOnUiThread
                    }
                    quizzes = arr
                    videoFile = file
                    nextQuiz = 0
                    curQuiz = -1
                    stars = 0
                    finished = false
                    startVideo()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("连不上内容服务\n$server\n${e.message}\n\n（本机跑：./run.sh serve-eng）")
                }
            }
        }
    }

    private fun startVideo() {
        status("正在加载动画 …")
        video.setVideoURI(Uri.parse("$server/video/$videoFile"))
        video.setOnPreparedListener { mp ->
            statusView.visibility = View.GONE
            mp.isLooping = false
            mp.start()
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    /** 轮询播放进度：到点就暂停出题。 */
    private fun maybeQuiz() {
        if (finished || locked) return
        if (quizLayer.visibility == View.VISIBLE) return
        val arr = quizzes ?: return
        if (nextQuiz >= arr.length()) return
        if (!video.isPlaying) return
        val atMs = arr.getJSONObject(nextQuiz).optLong("at_ms", Long.MAX_VALUE)
        if (video.currentPosition >= atMs) startQuiz(nextQuiz)
    }

    private fun startQuiz(i: Int) {
        val arr = quizzes ?: return
        val q = arr.getJSONObject(i)
        video.pause()
        curQuiz = i
        focusedOpt = 0
        wrongOnCurrent = 0
        locked = false

        val opts = q.getJSONArray("options")
        for (k in 0 until 3) {
            if (k < opts.length()) {
                val o = opts.getJSONObject(k)
                emojiViews[k].text = o.optString("emoji", "")
                labelViews[k].text = o.optString("label", "ABC".substring(k, k + 1))
                containers[k].visibility = View.VISIBLE
                containers[k].setBackgroundColor(BG_NORMAL)
            } else {
                containers[k].visibility = View.INVISIBLE
            }
        }
        updateFocus()
        headerView.text = "第 ${i + 1}/${arr.length()} 题     ⭐ $stars"
        promptView.text = "🔊 听一听，选出你听到的那个（按 ↑ 重播）"
        quizLayer.visibility = View.VISIBLE
        playUrl("$server/audio/" + q.getString("target_audio") + ".m4a")
    }

    private fun updateFocus() {
        for (k in 0 until 3) {
            if (containers[k].visibility == View.VISIBLE) {
                containers[k].setBackgroundColor(if (k == focusedOpt) BG_FOCUS else BG_NORMAL)
            }
        }
    }

    private fun choose(opt: Int) {
        if (locked || finished) return
        val arr = quizzes ?: return
        val i = curQuiz
        if (i < 0) return
        val q = arr.getJSONObject(i)
        val answer = q.getInt("answer")
        val targetSlug = q.getString("target_audio")

        if (opt == answer) {
            stars += 1
            locked = true
            containers[opt].setBackgroundColor(BG_CORRECT)
            promptView.text = "👍 对了！"
            headerView.text = "第 ${i + 1}/${arr.length()} 题     ⭐ $stars"
            playUrl("$server/audio/$targetSlug.m4a")   // 再播一遍，强化音↔图
            handler.postDelayed({ resumeAfterQuiz() }, NEXT_DELAY_MS)
        } else {
            wrongOnCurrent += 1
            containers[opt].setBackgroundColor(BG_WRONG)
            handler.postDelayed({ if (!locked && !finished) updateFocus() }, FLASH_MS)
            if (wrongOnCurrent >= MAX_WRONG) {
                locked = true
                val zh = q.optJSONArray("options")
                    ?.optJSONObject(answer)?.optString("zh", "").orEmpty()
                promptView.text = if (zh.isEmpty()) "是这个哦 👉" else "是这个 → $zh"
                containers[answer].setBackgroundColor(BG_CORRECT)
                playUrl("$server/audio/$targetSlug.m4a")
                handler.postDelayed({ resumeAfterQuiz() }, REVEAL_DELAY_MS)
            } else {
                promptView.text = "再来试试 🙌   ← → 选 · OK 确认"
                playUrl("$server/audio/retry.m4a")
            }
        }
    }

    /** 答完（对或已亮答案）：收起答题层，继续播动画。 */
    private fun resumeAfterQuiz() {
        quizLayer.visibility = View.GONE
        nextQuiz = curQuiz + 1
        curQuiz = -1
        locked = false
        if (!finished) video.start()
    }

    private fun showDone() {
        finished = true
        locked = false
        curQuiz = -1
        quizLayer.visibility = View.VISIBLE
        headerView.text = "🎬 小火车英语课堂"
        promptView.text = "看完啦！⭐ ×$stars\n\n按 OK 重看一遍   ·   按 返回 退出"
        for (k in 0 until 3) containers[k].visibility = View.INVISIBLE
    }

    private fun restart() {
        val arr = quizzes ?: return
        finished = false
        locked = false
        nextQuiz = 0
        curQuiz = -1
        stars = 0
        quizLayer.visibility = View.GONE
        video.seekTo(0)
        video.start()
        if (arr.length() > 0) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 答题层（含看完页）可见时才接管方向键
        if (quizLayer.visibility == View.VISIBLE) {
            if (finished) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                        restart(); return true
                    }
                    KeyEvent.KEYCODE_BACK -> { finish(); return true }
                }
                return super.onKeyDown(keyCode, event)
            }
            if (locked) return true
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { focusedOpt = (focusedOpt + 2) % 3; updateFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusedOpt = (focusedOpt + 1) % 3; updateFocus(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    quizzes?.let { arr ->
                        val i = curQuiz
                        if (i in 0 until arr.length()) {
                            playUrl("$server/audio/" + arr.getJSONObject(i).getString("target_audio") + ".m4a")
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    choose(focusedOpt); return true
                }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        // 播放中：返回退出；OK/空格 什么都不做（避免误触）
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun status(msg: String) {
        statusView.text = msg
        statusView.visibility = View.VISIBLE
    }

    private fun playUrl(url: String) {
        try { sfx?.setOnPreparedListener(null); sfx?.reset(); sfx?.release() } catch (_: Exception) {}
        sfx = MediaPlayer().apply {
            try {
                setDataSource(url)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            } catch (_: Exception) {
                try { release() } catch (_: Exception) {}
            }
        }
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        try { video.pause() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        try { video.stopPlayback() } catch (_: Exception) {}
        try { sfx?.release() } catch (_: Exception) {}
        sfx = null
    }
}
