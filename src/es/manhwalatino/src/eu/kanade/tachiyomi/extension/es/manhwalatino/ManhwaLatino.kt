package eu.kanade.tachiyomi.extension.es.manhwalatino

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ManhwaLatino : Madara(
    "Manhwa-Latino",
    "https://manhwa-latino.com",
    "es",
    SimpleDateFormat("dd/MM/yyyy", Locale("es")),
) {

    /**
     * CookieJar persistente para mantener la sesión de Cloudflare activa.
     * Guarda cookies como `cf_clearance`, `__cf_bm`, `PHPSESSID`, etc.
     */
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    private val persistentCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Filtra solo cookies relevantes
            val filtered = cookies.filter {
                it.name.startsWith("cf_") ||
                it.name.equals("PHPSESSID", true) ||
                it.name.equals("__cf_bm", true)
            }
            if (filtered.isNotEmpty()) {
                cookieStore[url.host] = filtered
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Devuelve las cookies guardadas para este dominio
            return cookieStore[url.host] ?: emptyList()
        }
    }

    // --- CLIENTE CON CLOUDFLARE BYPASS + COOKIES PERSISTENTES + REINTENTOS ---
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .cookieJar(persistentCookieJar)
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 2)
        .addInterceptor { chain ->
            var request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .build()
            request = request.newBuilder().headers(headers).build()

            var response = chain.proceed(request)
            var tryCount = 0

            // Reintenta si Cloudflare responde 503/403
            while ((response.code == 503 || response.code == 403) && tryCount < 3) {
                response.close()
                tryCount++
                Thread.sleep(2000L * tryCount)
                response = chain.proceed(request)
            }

            // Corrige tipo MIME de imágenes
            if (response.headers("Content-Type").contains("application/octet-stream") &&
                response.request.url.toString().endsWith(".jpg")
            ) {
                val orgBody = response.body.source()
                val newBody = orgBody.asResponseBody("image/jpeg".toMediaType())
                response.newBuilder()
                    .body(newBody)
                    .build()
            } else {
                response
            }
        }
        .build()

    // --- CONFIG MADARA ---
    override val useNewChapterEndpoint = true

    override val chapterUrlSelector = "div.mini-letters > a"
    override val mangaDetailsSelectorStatus =
        "div.post-content_item:contains(Estado del comic) > div.summary-content"
    override val mangaDetailsSelectorDescription =
        "div.post-content_item:contains(Resumen) div.summary-container"
    override val pageListParseSelector = "div.page-break img.wp-manga-chapter-img"

    // --- PARSEO DE CAPÍTULOS CON PAGINACIÓN ---
    private val chapterListNextPageSelector = "div.pagination > span.current + span"

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url
        var document = response.asJsoup()
        launchIO { countViews(document) }

        val chapterList = mutableListOf<SChapter>()
        var page = 1

        do {
            val chapterElements = document.select(chapterListSelector())
            if (chapterElements.isEmpty()) break

            chapterList.addAll(chapterElements.map { chapterFromElement(it) })

            val hasNextPage = document.select(chapterListNextPageSelector).isNotEmpty()
            if (hasNextPage) {
                page++
                val nextPageUrl = mangaUrl.newBuilder()
                    .setQueryParameter("t", page.toString())
                    .build()
                document = client.newCall(GET(nextPageUrl, headers)).execute().asJsoup()
            } else break
        } while (true)

        return chapterList
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)?.let { urlElement ->
                chapter.url = urlElement.attr("abs:href").let {
                    it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
                }
                chapter.name = urlElement.wholeText().substringAfter("\n")
            }

            chapter.date_upload = selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
                ?: selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
                ?: parseChapterDate(selectFirst(chapterDateSelector())?.text())
        }

        return chapter
    }
}
