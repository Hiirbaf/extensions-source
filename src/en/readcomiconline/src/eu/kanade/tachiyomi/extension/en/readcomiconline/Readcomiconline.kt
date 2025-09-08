package eu.kanade.tachiyomi.extension.en.readcomiconline

import android.content.SharedPreferences
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.lib.randomua.UserAgentType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        .setRandomUserAgent(userAgentType = UserAgentType.DESKTOP, filterInclude = listOf("chrome"))
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
    private val preferences: SharedPreferences by getPreferencesLazy()

    // ==================== Popular / Latest ====================
    override fun popularMangaSelector() = ".list-comic > .item > a:first-child"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/ComicList/MostPopular?page=$page", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.text()
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
    }
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun popularMangaNextPageSelector() = "ul.pager > li > a:contains(Next)"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Search ====================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty() && (if (filters.isEmpty()) getFilterList() else filters)
        .filterIsInstance<GenreList>().all { it.included.isEmpty() && it.excluded.isEmpty() }
        ) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                var pathSegmentAdded = false
                for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                    when (filter) {
                        is PublisherFilter -> if (filter.state.isNotEmpty()) {
                            addPathSegments("Publisher/${filter.state.replace(" ", "-")}")
                            pathSegmentAdded = true
                        }
                        is WriterFilter -> if (filter.state.isNotEmpty()) {
                            addPathSegments("Writer/${filter.state.replace(" ", "-")}")
                            pathSegmentAdded = true
                        }
                        is ArtistFilter -> if (filter.state.isNotEmpty()) {
                            addPathSegments("Artist/${filter.state.replace(" ", "-")}")
                            pathSegmentAdded = true
                        }
                        else -> {}
                    }
                    if (pathSegmentAdded) break
                }
                addPathSegment(
                    (if (filters.isEmpty()) getFilterList() else filters)
                        .filterIsInstance<SortFilter>().first().selected.toString()
                )
                addQueryParameter("page", page.toString())
            }.build()
            return GET(url, headers)
        } else {
            val url = "$baseUrl/AdvanceSearch".toHttpUrl().newBuilder().apply {
                addQueryParameter("comicName", query.trim())
                addQueryParameter("page", page.toString())
                for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                    when (filter) {
                        is Status -> addQueryParameter("status", arrayOf("", "Completed", "Ongoing")[filter.state])
                        is GenreList -> {
                            addQueryParameter("ig", filter.included.joinToString(","))
                            addQueryParameter("eg", filter.excluded.joinToString(","))
                        }
                        else -> {}
                    }
                }
            }.build()
            return GET(url, headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Manga Details ====================
    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("div.barContent")!!
        val manga = SManga.create()

        val titleElement = infoElement.selectFirst("a.bigChar")
        val fullTitle = titleElement?.attr("title")?.takeIf { it.isNotBlank() }
            ?: titleElement?.ownText()?.trim().orEmpty()
        manga.title = fullTitle

        manga.artist = infoElement.select("p:has(span:contains(Artist:)) > a").first()?.text()
        manga.author = infoElement.select("p:has(span:contains(Writer:)) > a").first()?.text()
        manga.genre = infoElement.select("p:has(span:contains(Genres:)) > *:gt(0)").text()
        val descriptionText = infoElement.select("p:has(span:contains(Summary:)) ~ p").text()
        manga.description = "Título completo: $fullTitle\n\n$descriptionText"

        manga.status = infoElement.select("p:has(span:contains(Status:))").first()?.text().orEmpty().let { parseStatus(it) }
        manga.thumbnail_url = document.select(".rightBox:eq(0) img").first()?.absUrl("src")

        return manga
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga)).asObservableSuccess().map { response ->
            val parsed = mangaDetailsParse(response)
            parsed.url = manga.url
            parsed.title = parsed.title // forzar título completo
            parsed.initialized = true
            parsed
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        captchaUrl?.let { GET(it, headers) }.also { captchaUrl = null } ?: super.mangaDetailsRequest(manga)

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ==================== Chapters ====================
    override fun chapterListSelector() = "table.listing tr:gt(1)"
    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()!!
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.selectFirst("td:eq(1)")?.text()?.let { dateFormat.tryParse(it) }
        return chapter
    }
    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())

    override fun pageListRequest(chapter: SChapter): Request {
        val qualitySuffix = if ((qualityPref() != "lq" && serverPref() != "s2") || (qualityPref() == "lq" && serverPref() == "s2")) {
            "&s=${serverPref()}&quality=${qualityPref()}&readType=1"
        } else {
            "&s=${serverPref()}&readType=1"
        }
        return GET(baseUrl + chapter.url + qualitySuffix, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        val encryptedLinks = mutableListOf<String>()
        val useSecondServer = serverPref() == "s2"
        val scripts = document.select("script")
        if (remoteConfigItem == null) throw IOException("Failed to retrieve configuration")

        for (script in scripts) {
            QuickJs.create().use {
                val eval = "let _encryptedString = ${Json.encodeToString(script.data().trimIndent())};" + "let _useServer2 = $useSecondServer;${remoteConfigItem!!.imageDecryptEval}"
                val evalResult = (it.evaluate(eval) as String).parseAs<List<String>>()
                encryptedLinks.addAll(evalResult)
            }
        }

        val finalLinks = if (remoteConfigItem!!.postDecryptEval != null) {
            QuickJs.create().use {
                val eval = "let _decryptedLinks = ${Json.encodeToString(encryptedLinks)};let _useServer2 = $useSecondServer;${remoteConfigItem!!.postDecryptEval}"
                (it.evaluate(eval) as String).parseAs<MutableList<String>>()
            }
        } else encryptedLinks

        return finalLinks.mapIndexedNotNull { idx, url ->
            if (!remoteConfigItem!!.shouldVerifyLinks) {
                Page(idx, imageUrl = url)
            } else {
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use {
                    if (it.isSuccessful) Page(idx, imageUrl = url) else null
                }
            }
        }
    }
}
