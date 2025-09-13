package eu.kanade.tachiyomi.extension.all.cubari

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteStorageUtils {

    // Cache para evitar múltiples solicitudes WebView
    companion object {
        const val TIMEOUT_SEC: Long = 8 // Reducido de 10 a 8
        const val DELAY_MILLIS: Long = 5000 // Reducido de 10000 a 5000
        const val CACHE_DURATION = 5 * 60 * 1000L // 5 minutos

        private val responseCache = ConcurrentHashMap<String, CacheEntry>()
        private val webViewPool = mutableListOf<WebView>()
        private const val MAX_POOL_SIZE = 2

        data class CacheEntry(
            val response: String,
            val timestamp: Long,
        )

        // Pool de WebViews para reutilización
        @Synchronized
        private fun getWebView(): WebView {
            return if (webViewPool.isNotEmpty()) {
                webViewPool.removeAt(0)
            } else {
                WebView(Injekt.get<Application>()).apply {
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        // Optimizaciones adicionales
                        blockNetworkImage = true
                        blockNetworkLoads = false
                        cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }
            }
        }

        @Synchronized
        private fun returnWebView(webView: WebView) {
            if (webViewPool.size < MAX_POOL_SIZE) {
                // Limpiar el WebView antes de devolverlo al pool
                webView.clearHistory()
                webView.clearCache(true)
                webViewPool.add(webView)
            } else {
                webView.destroy()
            }
        }

        private fun getCachedResponse(url: String): String? {
            val entry = responseCache[url] ?: return null
            return if (System.currentTimeMillis() - entry.timestamp < CACHE_DURATION) {
                entry.response
            } else {
                responseCache.remove(url)
                null
            }
        }

        private fun cacheResponse(url: String, response: String) {
            responseCache[url] = CacheEntry(response, System.currentTimeMillis())

            // Limpiar cache antigua
            if (responseCache.size > 50) {
                val cutoffTime = System.currentTimeMillis() - CACHE_DURATION
                val iterator = responseCache.entries.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value.timestamp < cutoffTime) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    abstract class GenericInterceptor(private val transparent: Boolean) : Interceptor {
        private val handler = Handler(Looper.getMainLooper())

        abstract val jsScript: String
        abstract fun urlModifier(originalUrl: String): String

        internal class JsInterface(private val latch: CountDownLatch, var payload: String = "") {
            @JavascriptInterface
            fun passPayload(passedPayload: String) {
                payload = passedPayload
                latch.countDown()
            }
        }

        @Synchronized
        override fun intercept(chain: Interceptor.Chain): Response {
            return try {
                val originalRequest = chain.request()
                val originalResponse = chain.proceed(originalRequest)

                // Verificar cache primero
                val modifiedUrl = urlModifier(originalRequest.url.toString())
                val cachedResponse = getCachedResponse(modifiedUrl)

                if (cachedResponse != null && !transparent) {
                    return originalResponse.newBuilder()
                        .body(cachedResponse.toResponseBody(originalResponse.body.contentType()))
                        .build()
                }

                proceedWithWebView(originalRequest, originalResponse)
            } catch (e: Exception) {
                throw IOException(e)
            }
        }

        @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
        private fun proceedWithWebView(request: Request, response: Response): Response {
            val latch = CountDownLatch(1)
            var webView: WebView? = null

            val origRequestUrl = request.url.toString()
            val modifiedUrl = urlModifier(origRequestUrl)
            val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
            val jsInterface = JsInterface(latch)

            handler.post {
                webView = getWebView()
                webView?.let { webview ->
                    webview.addJavascriptInterface(jsInterface, "android")
                    webview.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(jsScript) {}
                            if (transparent) {
                                latch.countDown()
                            }
                        }

                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            latch.countDown()
                        }
                    }

                    webview.loadUrl(modifiedUrl, headers)
                }
            }

            val success = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

            // Cleanup con delay reducido
            handler.postDelayed({
                webView?.let { returnWebView(it) }
            }, DELAY_MILLIS / 2,)

            return if (transparent) {
                response
            } else {
                if (success && jsInterface.payload.isNotEmpty()) {
                    // Cachear la respuesta
                    cacheResponse(modifiedUrl, jsInterface.payload)
                }

                response.newBuilder()
                    .body(jsInterface.payload.toResponseBody(response.body.contentType()))
                    .build()
            }
        }
    }

    class TagInterceptor : GenericInterceptor(true) {
        override val jsScript: String = """
           let dispatched = false;
           const timeoutId = setTimeout(() => {
               if (!dispatched) {
                   dispatched = true;
                   window.android.passPayload('[]');
               }
           }, 3000);

           window.addEventListener('history-ready', function () {
             if (!dispatched) {
               clearTimeout(timeoutId);
               dispatched = true;
               Promise.all(
                 [globalHistoryHandler.getAllPinnedSeries(), globalHistoryHandler.getAllUnpinnedSeries()]
               ).then(e => {
                 window.android.passPayload(JSON.stringify(e.flatMap(e => e)))
               }).catch(() => {
                 window.android.passPayload('[]');
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
           let dispatched = false;
           const timeoutId = setTimeout(() => {
               if (!dispatched) {
                   dispatched = true;
                   window.android.passPayload('[]');
               }
           }, 3000);
           
           (function () {
             if (!dispatched) {
               clearTimeout(timeoutId);
               dispatched = true;
               Promise.all(
                 [globalHistoryHandler.getAllPinnedSeries(), globalHistoryHandler.getAllUnpinnedSeries()]
               ).then(e => {
                 window.android.passPayload(JSON.stringify(e.flatMap(e => e)))
               }).catch(() => {
                 window.android.passPayload('[]');
               });
             }
           })();
        """

        override fun urlModifier(originalUrl: String): String = originalUrl
    }
}
