package eu.kanade.tachiyomi.extension.all.cubari

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
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

    companion object {
        const val TIMEOUT_SEC: Long = 8
        const val DELAY_MILLIS: Long = 5000
        const val CACHE_DURATION = 5 * 60 * 1000L
        const val POOL_TTL_ACTIVE = 3 * 60 * 1000L
        const val POOL_TTL_BACKGROUND = 30 * 1000L
        const val CLEANUP_INTERVAL = 30 * 1000L

        private val responseCache = ConcurrentHashMap<String, CacheEntry>()
        private val webViewPool = mutableListOf<PoolEntry>()
        private const val MAX_POOL_SIZE = 2

        private var isInBackground = false
        private var lastActivityTimestamp = System.currentTimeMillis()

        data class CacheEntry(
            val response: String,
            val timestamp: Long,
        )

        data class PoolEntry(
            val webView: WebView,
            var lastUsed: Long,
            var useCount: Int = 0,
            val createdAt: Long = System.currentTimeMillis(),
        )

        private val cleanupHandler = Handler(Looper.getMainLooper())
        private val cleanupRunnable = object : Runnable {
            override fun run() {
                cleanupIdleWebViews()
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL)
            }
        }

        // Memory callback para detectar presión de memoria
        private val memoryCallback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {}

            override fun onLowMemory() {
                cleanup()
            }

            override fun onTrimMemory(level: Int) {
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cleanup()

                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                        // App en foreground con presión de memoria
                        isInBackground = false
                        markActive()
                    }

                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                        isInBackground = true
                        cleanupHandler.postDelayed({
                            if (isInBackground) cleanup()
                        }, 60 * 1000L,)
                    }

                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                        isInBackground = true
                        cleanupIdleWebViews()
                    }
                }
            }
        }

        init {
            // Registrar callback de memoria
            try {
                Injekt.get<Application>().registerComponentCallbacks(memoryCallback)
            } catch (e: Exception) {
                // Si falla, no es crítico
            }

            // Inicia limpieza periódica
            cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL)
        }

        /**
         * Llamar cuando se usa la extensión activamente
         */
        @Synchronized
        fun markActive() {
            isInBackground = false
            lastActivityTimestamp = System.currentTimeMillis()
        }

        /**
         * Detecta automáticamente si estamos inactivos
         */
        private fun isInactive(): Boolean {
            val inactiveTime = System.currentTimeMillis() - lastActivityTimestamp
            return inactiveTime > 60 * 1000L // 1 minuto sin actividad
        }

        @Synchronized
        private fun cleanupIdleWebViews() {
            // TTL dinámico basado en el estado
            val ttl = when {
                isInBackground -> POOL_TTL_BACKGROUND // 30 segundos
                isInactive() -> POOL_TTL_BACKGROUND // 30 segundos
                else -> POOL_TTL_ACTIVE // 3 minutos
            }

            val cutoffTime = System.currentTimeMillis() - ttl
            val iterator = webViewPool.iterator()
            var removed = 0

            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.lastUsed < cutoffTime) {
                    try {
                        entry.webView.destroy()
                        removed++
                    } catch (e: Exception) {
                        // Ignorar errores de destrucción
                    }
                    iterator.remove()
                }
            }

            if (removed > 0) {
                val state = if (isInBackground) "background" else if (isInactive()) "inactive" else "active"
                println("RemoteStorageUtils: Cleaned up $removed idle WebView(s) [state: $state]")
            }
        }

        @Synchronized
        private fun getWebView(): WebView {
            // Marcar actividad
            markActive()

            // Limpia WebViews antiguas antes de obtener una
            cleanupIdleWebViews()

            return if (webViewPool.isNotEmpty()) {
                val entry = webViewPool.removeAt(0)
                entry.lastUsed = System.currentTimeMillis()
                entry.useCount++
                entry.webView
            } else {
                createNewWebView()
            }
        }

        private fun createNewWebView(): WebView {
            return WebView(Injekt.get<Application>()).apply {
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    blockNetworkImage = true
                    blockNetworkLoads = false
                    cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
            }
        }

        @Synchronized
        private fun returnWebView(webView: WebView) {
            try {
                if (webViewPool.size < MAX_POOL_SIZE) {
                    webView.clearHistory()
                    webView.clearCache(false)
                    webView.removeJavascriptInterface("android")
                    webView.loadUrl("about:blank")

                    webViewPool.add(
                        PoolEntry(
                            webView = webView,
                            lastUsed = System.currentTimeMillis(),
                            useCount = 0,
                        ),
                    )
                } else {
                    // Pool lleno, destruir inmediatamente
                    webView.destroy()
                }
            } catch (e: Exception) {
                try {
                    webView.destroy()
                } catch (ignored: Exception) {
                }
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

            // Limpiar cache antigua si crece mucho
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

        /**
         * Limpieza manual de recursos. Llamar cuando sea necesario liberar memoria.
         * Útil para onLowMemory() o onTrimMemory()
         */
        @Synchronized
        fun cleanup() {
            // Destruir todas las WebViews del pool
            webViewPool.forEach { entry ->
                try {
                    entry.webView.destroy()
                } catch (e: Exception) {
                    // Ignorar errores
                }
            }
            webViewPool.clear()

            // Limpiar cache de respuestas
            responseCache.clear()

            println("RemoteStorageUtils: Full cleanup completed")
        }

        /**
         * Obtiene estadísticas del pool para debugging
         */
        @Synchronized
        fun getPoolStats(): String {
            val state = if (isInBackground) "background" else if (isInactive()) "inactive" else "active"
            return "WebView Pool: ${webViewPool.size}/$MAX_POOL_SIZE | Cache: ${responseCache.size} entries | State: $state"
        }

        /**
         * Limpieza completa al cerrar
         */
        fun shutdown() {
            try {
                cleanupHandler.removeCallbacks(cleanupRunnable)
                Injekt.get<Application>().unregisterComponentCallbacks(memoryCallback)
                cleanup()
            } catch (e: Exception) {
                // Ignorar errores
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
                    webview.settings.userAgentString = request.header("User-Agent")
                    webview.addJavascriptInterface(jsInterface, "android")

                    webview.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(jsScript) {}
                            if (transparent) {
                                latch.countDown()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            latch.countDown()
                        }
                    }

                    webview.loadUrl(modifiedUrl, headers)
                }
            }

            val success = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

            // Devolver WebView al pool después de un pequeño delay
            handler.postDelayed({
                webView?.let { returnWebView(it) }
            }, DELAY_MILLIS / 2,)

            return if (transparent) {
                response
            } else {
                if (success && jsInterface.payload.isNotEmpty()) {
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
