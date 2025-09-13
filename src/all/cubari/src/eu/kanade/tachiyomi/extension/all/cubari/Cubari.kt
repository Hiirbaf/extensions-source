package eu.kanade.tachiyomi.extension.all.cubari

import android.app.Application
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteStorageUtils {

    abstract class GenericInterceptor(private val transparent: Boolean) : Interceptor {
        private val latchTimeout = TIMEOUT_SEC
        abstract val jsScript: String
        abstract fun urlModifier(originalUrl: String): String

        internal class JsInterface(
            private val latch: CountDownLatch,
        ) {
            @Volatile var payload: String = ""

            @JavascriptInterface
            fun passPayload(passedPayload: String) {
                payload = passedPayload
                latch.countDown()
            }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            return try {
                proceedWithWebView(request, response)
            } catch (e: Exception) {
                throw IOException(e)
            }
        }

        private fun proceedWithWebView(request: Request, response: Response): Response {
            val latch = CountDownLatch(1)
            val jsInterface = JsInterface(latch)

            val webview = WebView(Injekt.get<Application>()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                userAgentString = request.header("User-Agent")

                addJavascriptInterface(jsInterface, "android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(jsScript) { _ ->
                            if (transparent) latch.countDown()
                            destroy() // 🔹 Destrucción temprana
                        }
                    }
                }
            }

            val headers = request.headers.toMultimap()
                .mapValues { it.value.firstOrNull().orEmpty() }

            webview.loadUrl(urlModifier(request.url.toString()), headers)

            latch.await(latchTimeout, TimeUnit.SECONDS)

            return if (transparent) {
                response
            } else {
                response.newBuilder()
                    .body(jsInterface.payload.toResponseBody(response.body?.contentType()))
                    .build()
            }
        }
    }

    class TagInterceptor : GenericInterceptor(true) {
        override val jsScript: String = """
           let dispatched = false;
           window.addEventListener('history-ready', function () {
             if (!dispatched) {
               dispatched = true;
               Promise.all([
                 globalHistoryHandler.getAllPinnedSeries(),
                 globalHistoryHandler.getAllUnpinnedSeries()
               ]).then(e => {
                 window.android.passPayload(JSON.stringify(e.flatMap(e => e)))
               });
             }
           });
           tag();
        """
        override fun urlModifier(originalUrl: String): String {
            return originalUrl.replace("/api/", "/").replace("/series/", "/")
        }
    }

    class HomeInterceptor : GenericInterceptor(false) {
        override val jsScript: String = """
           (function () {
             Promise.all([
               globalHistoryHandler.getAllPinnedSeries(),
               globalHistoryHandler.getAllUnpinnedSeries()
             ]).then(e => {
               window.android.passPayload(JSON.stringify(e.flatMap(e => e)))
             });
           })();
        """
        override fun urlModifier(originalUrl: String): String = originalUrl
    }

    companion object {
        const val TIMEOUT_SEC: Long = 10
    }
}

/**
 * 🔹 Clientes reutilizables para no recrearlos en cada request
 */
val baseClient: OkHttpClient by lazy { OkHttpClient() }

val homeClient: OkHttpClient by lazy {
    baseClient.newBuilder()
        .addInterceptor(RemoteStorageUtils.HomeInterceptor())
        .build()
}

val tagClient: OkHttpClient by lazy {
    baseClient.newBuilder()
        .addInterceptor(RemoteStorageUtils.TagInterceptor())
        .build()
}

/**
 * 🔹 Parser único y reutilizable
 */
val jsonParser: Json by lazy {
    Json { ignoreUnknownKeys = true }
}

fun parseJson(body: String): JsonElement =
    jsonParser.parseToJsonElement(body)
