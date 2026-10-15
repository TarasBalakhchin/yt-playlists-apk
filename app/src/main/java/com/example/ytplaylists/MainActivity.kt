package com.example.ytplaylists

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingImportCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportJson: String = ""

    companion object {
        private const val REQ_IMPORT_JSON = 1001
        private const val REQ_EXPORT_JSON = 1002
        private const val DEFAULT_COOKIES = "SOCS=CAISHAgBEhJnd3NfMjAyNDA1MTQtMF9SQzIaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20230531-04-p0.ru+FX+999"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0f0f12")
        window.navigationBarColor = Color.parseColor("#0f0f12")

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView)
        setContentView(root)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        webView.setBackgroundColor(Color.parseColor("#0f0f12"))
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                vw: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingImportCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQ_IMPORT_JSON)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                handleIncomingShareIntent(intent)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    // Перехват ссылки из кнопки «Поделиться» в приложении YouTube
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            intent.action = ""

            val listMatcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(sharedText)
            val videoMatcher = Pattern.compile("(?:[?&]v=|youtu\\.be/)([a-zA-Z0-9_-]{11})").matcher(sharedText)

            val listId = if (listMatcher.find()) listMatcher.group(1) else null
            val videoId = if (videoMatcher.find()) videoMatcher.group(1) else null

            if (!listId.isNullOrEmpty()) {
                fetchPlaylistFromYouTube(listId, videoId)
            } else {
                sendStatusToUi("В ссылке не найден ID плейлиста (list=...)")
            }
        }
    }

    // Многоуровневая загрузка плейлиста:
    // 1) Внутренний YouTube InnerTube API (/youtubei/v1/browse и /youtubei/v1/next)
    // 2) Загрузка веб-страницы с обходом окна согласия (SOCS Cookie)
    // 3) Универсальный парсер всех форматов (включая новый lockupViewModel 2025/2026)
    private fun fetchPlaylistFromYouTube(listId: String, seedVideoId: String? = null) {
        sendStatusToUi("Загружаем видео плейлиста...")
        thread {
            try {
                val collectedVideos = LinkedHashMap<String, String>()
                var playlistTitle = ""

                // Шаг 1. Пробуем официальный внутренний API YouTube (InnerTube browse)
                val browseJson = callInnerTubeApi(
                    endpoint = "browse",
                    payload = JSONObject().apply {
                        put("browseId", if (listId.startsWith("VL")) listId else "VL$listId")
                        put("context", createInnerTubeContext("WEB", "2.20250312.04.00"))
                    }
                )
                if (browseJson != null) {
                    val title = extractPlaylistTitle(browseJson)
                    if (title.isNotEmpty()) playlistTitle = title
                    extractAllVideosUniversal(browseJson, collectedVideos)
                }

                // Шаг 2. Если видео не нашлись (например, микс или нестандартный лист) — пробуем InnerTube next
                if (collectedVideos.isEmpty()) {
                    val nextJson = callInnerTubeApi(
                        endpoint = "next",
                        payload = JSONObject().apply {
                            put("playlistId", listId)
                            if (!seedVideoId.isNullOrEmpty()) put("videoId", seedVideoId)
                            put("context", createInnerTubeContext("WEB", "2.20250312.04.00"))
                        }
                    )
                    if (nextJson != null) {
                        if (playlistTitle.isEmpty()) playlistTitle = extractPlaylistTitle(nextJson)
                        extractAllVideosUniversal(nextJson, collectedVideos)
                    }
                }

                // Шаг 3. Если всё ещё пусто — скачиваем HTML-страницу с куками согласия SOCS
                if (collectedVideos.isEmpty()) {
                    val html = fetchHtmlWithCookies("https://www.youtube.com/playlist?list=$listId&hl=ru")
                    val initialData = extractYtInitialDataJson(html)
                    if (initialData != null) {
                        if (playlistTitle.isEmpty()) playlistTitle = extractPlaylistTitle(initialData)
                        extractAllVideosUniversal(initialData, collectedVideos)
                    }
                }

                if (playlistTitle.isEmpty()) {
                    playlistTitle = "Плейлист ${listId.take(6)}"
                }

                // Формируем нормализованный JSON для передачи в интерфейс index.html
                val normalizedData = buildNormalizedInitialData(playlistTitle, collectedVideos)
                val safeJson = JSONObject.quote(normalizedData.toString())
                val safeListId = JSONObject.quote(listId)

                runOnUiThread {
                    webView.evaluateJavascript("window.receiveRawPlaylistData($safeListId, $safeJson);", null)
                    if (collectedVideos.isEmpty()) {
                        webView.evaluateJavascript(
                            "window.setStatusFromNative('Внимание: найдено 0 видео. Проверьте, что доступ к плейлисту в YouTube открыт («По ссылке» или «Открытый», а не «Личный»).');",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                sendStatusToUi("Ошибка сети при загрузке плейлиста.")
            }
        }
    }

    private fun createInnerTubeContext(clientName: String, clientVersion: String): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "ru")
                put("gl", "RU")
            })
        }
    }

    private fun callInnerTubeApi(endpoint: String, payload: JSONObject): JSONObject? {
        return try {
            val url = URL("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cookie", DEFAULT_COOKIES)
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                connectTimeout = 10000
                readTimeout = 10000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(resp)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchHtmlWithCookies(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Cookie", DEFAULT_COOKIES)
            setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            connectTimeout = 10000
            readTimeout = 10000
        }
        return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
    }

    // Точное извлечение объекта ytInitialData по балансу фигурных скобок
    private fun extractYtInitialDataJson(html: String): JSONObject? {
        val marker = "var ytInitialData = "
        val startIdx = html.indexOf(marker)
        if (startIdx == -1) return null

        val jsonStart = html.indexOf('{', startIdx + marker.length)
        if (jsonStart == -1) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in jsonStart until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return try {
                            JSONObject(html.substring(jsonStart, i + 1))
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractText(node: Any?): String {
        if (node == null) return ""
        if (node is String) return node.trim()
        if (node is JSONObject) {
            val simple = node.optString("simpleText", "").trim()
            if (simple.isNotEmpty()) return simple

            val content = node.optString("content", "").trim()
            if (content.isNotEmpty()) return content

            val runs = node.optJSONArray("runs")
            if (runs != null && runs.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until runs.length()) {
                    sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                }
                if (sb.isNotEmpty()) return sb.toString().trim()
            }

            val dyn = node.optJSONObject("dynamicTextViewModel")?.optJSONObject("text")?.optString("content", "")?.trim()
            if (!dyn.isNullOrEmpty()) return dyn
        }
        return ""
    }

    private fun extractPlaylistTitle(root: JSONObject): String {
        val metaTitle = root.optJSONObject("metadata")
            ?.optJSONObject("playlistMetadataRenderer")
            ?.optString("title", "")?.trim()
        if (!metaTitle.isNullOrEmpty()) return metaTitle

        val header = root.optJSONObject("header")
        if (header != null) {
            val h1 = extractText(header.optJSONObject("playlistHeaderRenderer")?.opt("title"))
            if (h1.isNotEmpty()) return h1

            val h2 = header.optJSONObject("pageHeaderRenderer")?.optString("pageTitle", "")?.trim()
            if (!h2.isNullOrEmpty()) return h2
        }
        return ""
    }

    // Универсальный обход дерева JSON: находит видео во всех старых и новых форматах YouTube
    private fun extractAllVideosUniversal(node: Any?, outMap: LinkedHashMap<String, String>) {
        when (node) {
            is JSONObject -> {
                // 1. Классические и мобильные рендереры видео в плейлисте
                val rendererKeys = arrayOf(
                    "playlistVideoRenderer",
                    "playlistPanelVideoRenderer",
                    "videoRenderer",
                    "compactVideoRenderer",
                    "videoWithContextRenderer",
                    "gridVideoRenderer"
                )
                for (key in rendererKeys) {
                    val r = node.optJSONObject(key)
                    if (r != null) {
                        val vId = r.optString("videoId", "").trim()
                        val vTitle = extractText(r.opt("title")).ifEmpty { extractText(r.opt("headline")) }
                        if (vId.length == 11 && vTitle.isNotEmpty() && !outMap.containsKey(vId)) {
                            outMap[vId] = vTitle
                        }
                        return
                    }
                }

                // 2. Новый формат YouTube 2025/2026: lockupViewModel
                val lockup = node.optJSONObject("lockupViewModel")
                if (lockup != null) {
                    val cId = lockup.optString("contentId", "").trim()
                    val titleObj = lockup.optJSONObject("metadata")
                        ?.optJSONObject("lockupMetadataViewModel")
                        ?.opt("title")
                    val vTitle = extractText(titleObj)
                    if (cId.length == 11 && vTitle.isNotEmpty() && !outMap.containsKey(cId)) {
                        outMap[cId] = vTitle
                    }
                    return
                }

                // Рекурсивный обход всех дочерних полей
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    extractAllVideosUniversal(node.opt(k), outMap)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractAllVideosUniversal(node.opt(i), outMap)
                }
            }
        }
    }

    // Упаковка найденных роликов в компактную структуру для index.html
    private fun buildNormalizedInitialData(title: String, videos: Map<String, String>): JSONObject {
        val itemsArray = JSONArray()
        for ((vId, vTitle) in videos) {
            val pvr = JSONObject().apply {
                put("videoId", vId)
                put("title", JSONObject().apply {
                    put("simpleText", vTitle)
                })
            }
            itemsArray.put(JSONObject().apply {
                put("playlistVideoRenderer", pvr)
            })
        }

        return JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("playlistMetadataRenderer", JSONObject().apply {
                    put("title", title)
                })
            })
            put("contents", itemsArray)
        }
    }

    private fun sendStatusToUi(msg: String) {
        val quoted = JSONObject.quote(msg)
        runOnUiThread {
            webView.evaluateJavascript("window.setStatusFromNative($quoted);", null)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun fetchPlaylistById(input: String) {
            val listMatcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(input)
            val videoMatcher = Pattern.compile("(?:[?&]v=|youtu\\.be/)([a-zA-Z0-9_-]{11})").matcher(input)
            val listId = if (listMatcher.find()) listMatcher.group(1)!! else input.trim()
            val videoId = if (videoMatcher.find()) videoMatcher.group(1) else null
            fetchPlaylistFromYouTube(listId, videoId)
        }

        @JavascriptInterface
        fun fetchAllPlaylistsFromUrl(pageUrl: String) {
            sendStatusToUi("Сканируем страницу на наличие плейлистов...")
            thread {
                try {
                    val html = fetchHtmlWithCookies(pageUrl)
                    val matcher = Pattern.compile("\"playlistId\":\"(PL[a-zA-Z0-9_-]+|OL[a-zA-Z0-9_-]+|FL[a-zA-Z0-9_-]+)\"").matcher(html)
                    val ids = LinkedHashSet<String>()
                    while (matcher.find()) {
                        matcher.group(1)?.let { ids.add(it) }
                    }

                    if (ids.isEmpty()) {
                        sendStatusToUi("На этой странице не найдено открытых плейлистов.")
                        return@thread
                    }

                    sendStatusToUi("Найдено плейлистов: ${ids.size}. Загружаем видео...")
                    for (id in ids) {
                        fetchPlaylistFromYouTube(id)
                        Thread.sleep(350)
                    }
                } catch (e: Exception) {
                    sendStatusToUi("Ошибка чтения страницы плейлистов.")
                }
            }
        }

        @JavascriptInterface
        fun openInYouTubeApp(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.google.android.youtube")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        @JavascriptInterface
        fun saveJsonToFile(jsonContent: String, fileName: String) {
            pendingExportJson = jsonContent
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            startActivityForResult(intent, REQ_EXPORT_JSON)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_JSON) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            pendingImportCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            pendingImportCallback = null
        } else if (requestCode == REQ_EXPORT_JSON && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingExportJson.toByteArray(Charsets.UTF_8))
                }
                sendStatusToUi("✓ Файл успешно сохранён на телефон!")
            }
        }
    }
}
