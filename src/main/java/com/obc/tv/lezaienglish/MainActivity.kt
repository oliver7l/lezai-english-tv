package com.obc.tv.lezaienglish

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 乐仔学英语 · 听音选图（A/B/C）。
 *
 * 数据来源：局域网 HTTP（tools/serve_english.py，跑在 mini96）。
 *   GET /quiz.json → { questions:[ { target_audio, target_word,
 *                                    options:[{slug,emoji,zh,label}], answer } ] }
 *   GET /audio/<slug>.m4a   单词发音；retry.m4a 是错的温和提示音。
 *
 * 玩法：
 *   每题自动播目标词发音（按 ↑ 重播）→ 屏幕出 A/B/C 三张图 →
 *   ← / → 移焦点，OK = 选定。
 *   对 → 再播该词 + ⭐+1 + 下一题；
 *   错 → 播 retry（温和「再来试试」，不批评）；连错 2 次亮出正确答案后进下一题。
 *   一轮结束 → 大字「完成啦 ⭐×N」，OK 再来一轮；BACK = 退出。
 *
 * 服务器地址优先级：Intent extra "server" > SharedPreferences > 默认 192.168.1.96:8485。
 *   adb 指定：am start -n com.obc.tv.lezaienglish/.MainActivity --es server http://IP:PORT
 *
 * 零第三方依赖：HttpURLConnection + org.json + MediaPlayer 都在 framework 里。
 */
class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_SERVER = "http://192.168.1.96:8485"
        private const val PREFS = "english_prefs"
        private const val KEY_SERVER = "server_url"
        private const val NEXT_DELAY_MS = 1600L
        private const val REVEAL_DELAY_MS = 2600L
        private const val FLASH_MS = 450L
        private const val MAX_WRONG = 2

        private const val BG_NORMAL = 0x22FFFFFF.toInt()      // 半透明白底
        private const val BG_FOCUS = 0xFF1F6FEB.toInt()       // 焦点蓝
        private const val BG_CORRECT = 0xFF2EA043.toInt()     // 对=绿
        private const val BG_WRONG = 0xFF9E6A00.toInt()       // 错=温和琥珀（不用刺眼红）
    }

    private lateinit var headerView: TextView
    private lateinit var promptView: TextView
    private lateinit var containers: List<LinearLayout>
    private lateinit var labelViews: List<TextView>
    private lateinit var emojiViews: List<TextView>

    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null

    private var server: String = DEFAULT_SERVER
    private var questions: JSONArray? = null
    private var qIndex = 0
    private var focusedOpt = 0
    private var stars = 0
    private var wrongOnCurrent = 0
    private var finished = false
    private var locked = false          // 反馈播放期暂时锁住按键

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        headerView = findViewById(R.id.header)
        promptView = findViewById(R.id.prompt)
        containers = listOf(findViewById(R.id.opt0), findViewById(R.id.opt1), findViewById(R.id.opt2))
        labelViews = listOf(findViewById(R.id.lbl0), findViewById(R.id.lbl1), findViewById(R.id.lbl2))
        emojiViews = listOf(findViewById(R.id.emo0), findViewById(R.id.emo1), findViewById(R.id.emo2))

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        server = intent?.getStringExtra("server")
            ?: prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        server = server.trimEnd('/')

        loadQuiz()
    }

    /** 拉 quiz.json，成功则从第 1 题开始。 */
    private fun loadQuiz() {
        promptView.text = "正在连接 $server …"
        promptView.visibility = View.VISIBLE
        pool.execute {
            try {
                val json = httpGetText("$server/quiz.json")
                val arr = JSONObject(json).getJSONArray("questions")
                runOnUiThread {
                    if (arr.length() == 0) {
                        promptView.text = "服务器上没题目（$server/quiz.json）"
                    } else {
                        questions = arr
                        qIndex = 0
                        stars = 0
                        finished = false
                        showQuestion()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    promptView.text =
                        "连不上内容服务\n$server\n${e.message}\n\n（在本机跑：./run.sh serve-eng）"
                }
            }
        }
    }

    private fun showQuestion() {
        val arr = questions ?: return
        val q = arr.getJSONObject(qIndex)
        wrongOnCurrent = 0
        focusedOpt = 0
        locked = false

        val opts = q.getJSONArray("options")
        for (i in 0 until 3) {
            if (i < opts.length()) {
                val o = opts.getJSONObject(i)
                emojiViews[i].text = o.optString("emoji", "")
                labelViews[i].text = o.optString("label", "ABC".substring(i, i + 1))
                containers[i].visibility = View.VISIBLE
                containers[i].setBackgroundColor(BG_NORMAL)
            } else {
                containers[i].visibility = View.INVISIBLE
            }
        }
        updateFocus()
        updateHeader()
        promptView.text = "🔊 听一听，选出你听到的那个（按 ↑ 重播）"
        playUrl(audioUrl(q.getString("target_audio")))
    }

    private fun updateFocus() {
        for (i in 0 until 3) {
            if (containers[i].visibility == View.VISIBLE) {
                containers[i].setBackgroundColor(if (i == focusedOpt) BG_FOCUS else BG_NORMAL)
            }
        }
    }

    private fun updateHeader() {
        val total = questions?.length() ?: 0
        headerView.text = "第 ${qIndex + 1}/$total 题     ⭐ $stars"
    }

    private fun choose(i: Int) {
        if (locked || finished) return
        val q = questions?.getJSONObject(qIndex) ?: return
        val correctIdx = q.getInt("answer")
        val targetSlug = q.getString("target_audio")

        if (i == correctIdx) {
            stars += 1
            locked = true
            containers[i].setBackgroundColor(BG_CORRECT)
            promptView.text = "👍 对了！"
            updateHeader()
            playUrl(audioUrl(targetSlug))           // 再播一遍，强化「音↔图」
            handler.postDelayed({ nextQuestion() }, NEXT_DELAY_MS)
        } else {
            wrongOnCurrent += 1
            containers[i].setBackgroundColor(BG_WRONG)
            handler.postDelayed({ if (!locked && !finished) updateFocus() }, FLASH_MS)
            if (wrongOnCurrent >= MAX_WRONG) {
                // 连错两次：温和亮出正确项 + 播单词，再进下一题（不卡死、不批评）
                locked = true
                val zh = q.optJSONArray("options")
                    ?.optJSONObject(correctIdx)?.optString("zh", "").orEmpty()
                promptView.text = if (zh.isEmpty()) "是这个哦 👉" else "是这个 → $zh"
                containers[correctIdx].setBackgroundColor(BG_CORRECT)
                playUrl(audioUrl(targetSlug))
                handler.postDelayed({ nextQuestion() }, REVEAL_DELAY_MS)
            } else {
                promptView.text = "再来试试 🙌   ← → 选 · OK 确认"
                playUrl("$server/audio/retry.m4a")
            }
        }
    }

    private fun nextQuestion() {
        val arr = questions ?: return
        qIndex += 1
        if (qIndex >= arr.length()) {
            finished = true
            headerView.text = "🎉 完成啦     ⭐ ×$stars"
            promptView.text = "按 OK 再来一轮     ·   按 返回 退出"
            for (i in 0 until 3) containers[i].visibility = View.INVISIBLE
        } else {
            showQuestion()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (finished) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    qIndex = 0
                    stars = 0
                    finished = false
                    showQuestion()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        if (locked) return true                 // 反馈播放期吃掉所有键，防误触
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { focusedOpt = (focusedOpt + 2) % 3; updateFocus(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { focusedOpt = (focusedOpt + 1) % 3; updateFocus(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {       // 重播当前目标词
                val q = questions?.getJSONObject(qIndex)
                if (q != null) playUrl(audioUrl(q.getString("target_audio")))
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                choose(focusedOpt); return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun audioUrl(slug: String) = "$server/audio/$slug.m4a"

    /** 播放远程 m4a；出错就吞掉（不能因为一段音失败卡住整个玩法）。 */
    private fun playUrl(url: String) {
        try { player?.setOnPreparedListener(null); player?.reset(); player?.release() } catch (_: Exception) {}
        player = MediaPlayer().apply {
            try {
                setDataSource(url)
                setOnPreparedListener { mp -> mp.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            } catch (_: Exception) {
                try { release() } catch (_: Exception) {}
            }
        }
    }

    private fun httpGetText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.connect()
        return conn
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        try { player?.pause() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
