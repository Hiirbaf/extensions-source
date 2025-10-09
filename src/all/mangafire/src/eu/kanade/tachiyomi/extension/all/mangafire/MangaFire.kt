package eu.kanade.tachiyomi.extension.all.mangafire

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MangaFire(
    override val lang: String,
    private val langCode: String = lang,
) : ConfigurableSource, HttpSource() {
    override val name = "MangaFire"

    override val baseUrl = "https://mangafire.to"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    override val client = network.cloudflareClient.newBuilder().addInterceptor(ImageInterceptor).build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        return searchMangaRequest(
            page,
            "",
            FilterList(SortFilter(defaultValue = "most_viewed")),
        )
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return searchMangaRequest(
            page,
            "",
            FilterList(SortFilter(defaultValue = "recently_updated")),
        )
    }

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filter")

            if (query.isNotBlank()) {
                addQueryParameter("keyword", query)
            }

            val filterList = filters.ifEmpty { getFilterList() }
            filterList.filterIsInstance<UriFilter>().forEach {
                it.addToUri(this)
            }

            addQueryParameter("language[]", langCode)
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        var entries = document.select(searchMangaSelector()).map(::searchMangaFromElement)
        if (preferences.getBoolean(SHOW_VOLUME_PREF, false)) {
            entries = entries.flatMapTo(ArrayList(entries.size * 2)) { manga ->
                val volume = SManga.create().apply {
                    url = manga.url + VOLUME_URL_SUFFIX
                    title = VOLUME_TITLE_PREFIX + manga.title
                    thumbnail_url = manga.thumbnail_url
                }
                listOf(manga, volume)
            }
        }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(entries, hasNextPage)
    }

    private fun searchMangaNextPageSelector() = ".page-item.active + .page-item .page-link"

    private fun searchMangaSelector() = ".original.card-lg .unit .inner"

    private fun searchMangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst(".info > a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.ownText()
        }
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // =============================== Filters ==============================

    override fun getFilterList() = FilterList(
        TypeFilter(),
        GenreFilter(),
        GenreModeFilter(),
        StatusFilter(),
        YearFilter(),
        MinChapterFilter(),
        SortFilter(),
    )

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.removeSuffix(VOLUME_URL_SUFFIX)

    override fun mangaDetailsParse(response: Response): SManga {
        return mangaDetailsParse(response.asJsoup()).apply {
            if (response.request.url.fragment == VOLUME_URL_FRAGMENT) {
                title = VOLUME_TITLE_PREFIX + title
            }
        }
    }

    private fun mangaDetailsParse(document: Document) = SManga.create().apply {
        with(document.selectFirst(".main-inner:not(.manga-bottom)")!!) {
            title = selectFirst("h1")!!.text()
            thumbnail_url = selectFirst(".poster img")?.attr("src")
            status = selectFirst(".info > p").parseStatus()
            description = buildString {
                document.selectFirst("#synopsis .modal-content")?.textNodes()?.let {
                    append(it.joinToString("\n\n"))
                }

                selectFirst("h6")?.let {
                    append("\n\nAlternative title: ${it.text()}")
                }
            }.trim()

            selectFirst(".meta")?.let {
                author = it.selectFirst("span:contains(Author:) + span")?.text()
                val type = it.selectFirst("span:contains(Type:) + span")?.text()
                val genres = it.selectFirst("span:contains(Genres:) + span")?.text()
                genre = listOfNotNull(type, genres).joinToString()
            }
        }
    }

    private fun Element?.parseStatus(): Int = when (this?.text()?.lowercase()) {
        "releasing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "on_hiatus" -> SManga.ON_HIATUS
        "discontinued" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String {
        return baseUrl + chapter.url.substringBeforeLast("#")
    }

    private fun getAjaxRequest(ajaxType: String, mangaId: String, chapterType: String): Request {
        return GET("$baseUrl/ajax/$ajaxType/$mangaId/$chapterType/$langCode", headers)
    }

    @Serializable
    class AjaxReadDto(
        val html: String,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        throw UnsupportedOperationException()
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
        val document = response.asJsoup()

        val chapterElements = document.select(".tab-content[data-name=chapter] li.item")
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

        val chapters = chapterElements.map { el ->
            val a = el.selectFirst("a")!!
            val number = el.attr("data-number")
            val dateStr = el.selectFirst("span + span")?.text()?.trim().orEmpty()

            SChapter.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                name = a.selectFirst("span")?.text() ?: "Chapter $number"
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = try { dateFormat.parse(dateStr)?.time ?: 0L } catch (_: Exception) { 0L }
            }
        }

        return Observable.just(chapters)
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        // Usa la URL real del capítulo (sin ajax)
        return GET(baseUrl + chapter.url.substringBeforeLast("#"), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // 1️⃣ Intentar encontrar directamente las imágenes (HTML estándar)
        val images = document.select("img.viewer-image, .page-image, .container-reader img[data-src], .container-reader img[src]")

        if (images.isEmpty()) {
            // 2️⃣ Fallback: intentar parsear JSON embebido (algunos capítulos lo incluyen)
            val html = response.body.string()
            val regex = Regex("""\"images\":\[(.*?)\]""")
            val match = regex.find(html)
            if (match != null) {
                val jsonPart = "[${match.groupValues[1]}]"
                val urls = jsonPart.split(",").map { it.trim('"') }
                return urls.mapIndexed { i, url -> Page(i, imageUrl = url) }
            }

            throw Exception("No se encontraron imágenes en el capítulo.")
        }

        return images.mapIndexed { i, img ->
            val url = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            Page(i, imageUrl = url)
        }
    }

    @Serializable
    class PageListDto(private val images: List<List<JsonPrimitive>>) {
        val pages
            get() = images.map {
                Image(it[0].content, it[2].int)
            }
    }

    class Image(val url: String, val offset: Int)

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_VOLUME_PREF
            title = "Show volume entries in search result"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    // ============================= Utilities ==============================

    @Serializable
    class ResponseDto<T>(
        val result: T,
    )

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    private fun String.toBodyFragment(): Document {
        return Jsoup.parseBodyFragment(this, baseUrl)
    }

    companion object {
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        private const val SHOW_VOLUME_PREF = "show_volume"

        private const val VOLUME_URL_FRAGMENT = "vol"
        private const val VOLUME_URL_SUFFIX = "#$VOLUME_URL_FRAGMENT"
        private const val VOLUME_TITLE_PREFIX = "[VOL] "
    }
}
