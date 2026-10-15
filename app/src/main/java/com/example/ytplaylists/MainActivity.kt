package com.example.ytplaylists

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            intent.action = ""
            val matcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(sharedText)
            if (matcher.find()) {
                val listId = matcher.group(1) ?: return
                fetchPlaylistFromYouTube(listId)
            } else {
                sendStatusToUi("В переданной ссылке не найден ID плейлиста (list=...)")
            }
        }
    }

    private fun fetchPlaylistFromYouTube(listId: String) {
        sendStatusToUi("Загружаем плейлист из YouTube...")
        thread {
            try {
                val url = URL("https://www.youtube.com/playlist?list=$listId&hl=ru")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val html = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val marker = "var ytInitialData = "
                val start = html.indexOf(marker)
                if (start == -1) {
                    sendStatusToUi("Не удалось прочитать данные плейлиста.")
                    return@thread
                }
                val end = html.indexOf(";</script>", start)
                val rawJson = html.substring(start + marker.length, end)

                val safeJson = JSONObject.quote(rawJson)
                val safeListId = JSONObject.quote(listId)

                runOnUiThread {
                    webView.evaluateJavascript("window.receiveRawPlaylistData($safeListId, $safeJson);", null)
                }
            } catch (e: Exception) {
                sendStatusToUi("Ошибка сети при загрузке плейлиста.")
            }
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
        fun fetchPlaylistById(listId: String) {
            fetchPlaylistFromYouTube(listId)
        }

        @JavascriptInterface
        fun fetchAllPlaylistsFromUrl(pageUrl: String) {
            sendStatusToUi("Сканируем страницу на наличие плейлистов...")
            thread {
                try {
                    val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        )
                    }
                    val html = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val matcher = Pattern.compile("\"playlistId\":\"(PL[a-zA-Z0-9_-]+|OL[a-zA-Z0-9_-]+|FL[a-zA-Z0-9_-]+)\"").matcher(html)
                    val ids = LinkedHashSet<String>()
                    while (matcher.find()) {
                        matcher.group(1)?.let { ids.add(it) }
                    }

                    if (ids.isEmpty()) {
                        sendStatusToUi("На этой странице не найдено публичных плейлистов.")
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
