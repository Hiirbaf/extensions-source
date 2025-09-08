package eu.kanade.tachiyomi.extension.en.readcomiconline

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Readcomiconline : ConfigurableSource, ParsedHttpSource() {

    override val name = "ReadComicOnline"
    override val baseUrl = "https://readcomiconline.li"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(::captchaInterceptor)
        .build()

    private fun captchaInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val location = response.header("Location")
        if (location?.startsWith("/Special/AreYouHuman") == true) {
            captchaUrl = "$baseUrl/Special/AreYouHuman"
            throw Exception("Solve captcha in WebView")
        }
        return response
    }

    private var captchaUrl: String? = null
    private val preferences: SharedPreferences by lazy { 
        throw NotImplementedError("Preferences initialization needed") 
    }

    override fun popularMangaSelector() = ".list-comic > .item > a:first-child"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun popularMangaNextPageSelector() = "ul.pager > li > a:contains(Next)"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.text()
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("div.barContent")!!

        val manga = SManga.create()
        val titleElement = infoElement.selectFirst("a.bigChar")
        val fullTitle = titleElement?.attr("title")?.takeIf { it.isNotBlank() } ?: titleElement?.ownText()?.trim().orEmpty()
        manga.title = fullTitle
        manga.artist = infoElement.select("p:has(span:contains(Artist:)) > a").first()?.text()
        manga.author = infoElement.select("p:has(span:contains(Writer:)) > a").first()?.text()
        manga.genre = infoElement.select("p:has(span:contains(Genres:)) > *:gt(0)").text()

        val descriptionText = infoElement.select("p:has(span:contains(Summary:)) ~ p").text()
        manga.description = "Título completo: $fullTitle\n\n$descriptionText"

        manga.status = infoElement.select("p:has(span:contains(Status:))").first()?.text().orEmpty()
            .let { parseStatus(it) }
        manga.thumbnail_url = document.select(".rightBox:eq(0) img").first()?.absUrl("src")

        return manga
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga)).asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply {
                    url = manga.url
                    initialized = true
                }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        captchaUrl?.let { GET(it, headers) }.also { captchaUrl = null } ?: super.mangaDetailsRequest(manga)

    override fun chapterListSelector() = "table.listing tr:gt(1)"
    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.selectFirst("a")!!
        return SChapter.create().apply {
            setUrlWithoutDomain(urlElement.attr("href"))
            name = urlElement.text()
            date_upload = element.selectFirst("td:eq(1)")?.text()?.let { dateFormat.tryParse(it) }
        }
    }

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        // Simplificado para ejemplo
        return emptyList()
    }

    override fun imageUrlParse(document: Document) = ""

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
}
