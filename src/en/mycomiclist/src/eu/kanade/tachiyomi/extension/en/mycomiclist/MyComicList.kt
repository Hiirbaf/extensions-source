package eu.kanade.tachiyomi.extension.en.mycomiclist

import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MyComicList : ParsedHttpSource(), ConfigurableSource {

    override val name = "MyComicList"
    override val lang = "en"
    override val baseUrl = "https://mycomiclist.org"
    override val supportsLatest = true

    // -------------------------------------------------------------
    // Popular Manga
    // -------------------------------------------------------------

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/popular-comic?page=$page")

    override fun popularMangaSelector() =
        "div.comic-box"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.title = element.selectFirst("h3 a")?.text().orEmpty()
        manga.setUrlWithoutDomain(element.selectFirst("h3 a")?.attr("href").orEmpty())
        manga.thumbnail_url = element.selectFirst("img")?.attr("src")

        return manga
    }

    override fun popularMangaNextPageSelector() =
        "a.next"

    // -------------------------------------------------------------
    // Latest Manga
    // -------------------------------------------------------------

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/latest-comic?page=$page")

    override fun latestUpdatesSelector() =
        "div.comic-box"

    override fun latestUpdatesFromElement(element: Element) =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() =
        "a.next"

    // -------------------------------------------------------------
    // Search
    // -------------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl

        var selectedGenre: Tag? = null
        var selectedStatus: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> selectedGenre = filter.selectedTag
                is StateFilter -> {
                    selectedStatus = when (filter.state) {
                        1 -> "ongoing"
                        2 -> "finished"
                        else -> null
                    }
                }
            }
        }

        // Si seleccionó un género → ir directo a /genre-comic
        selectedGenre?.let { tag ->
            return GET("$baseUrl/${tag.key}-comic")
        }

        // Si es búsqueda normal
        return if (query.isNotBlank()) {
            GET("$baseUrl/search?q=$query")
        } else {
            GET(baseUrl)
        }
    }

    override fun searchMangaSelector() =
        "div.comic-box"

    override fun searchMangaFromElement(element: Element) =
        popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() =
        "a.next"

    // -------------------------------------------------------------
    // Manga Details
    // -------------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        val manga = SManga.create()

        manga.title = doc.selectFirst("h1.manga-title")?.text().orEmpty()
        manga.thumbnail_url = doc.selectFirst("div.manga-cover img")?.attr("src")
        manga.description = doc.select("div.manga-right > p").firstOrNull()?.text()

        val author = doc.selectFirst("td:contains(Author:) + td")?.text()
        manga.author = author
        manga.artist = author

        val genres = doc.select("td:contains(Genres:) + td a").map { it.text() }
        manga.genre = genres.joinToString(", ")

        val statusText = doc.selectFirst("td:contains(Status:) + td")?.text()?.lowercase()
        manga.status = when {
            statusText?.contains("ongoing") == true -> SManga.ONGOING
            statusText?.contains("complete") == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    // -------------------------------------------------------------
    // Chapters
    // -------------------------------------------------------------

    override fun chapterListSelector() =
        "ul.chapters-list li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        val a = element.selectFirst("a")!!

        chapter.name = a.text()
        chapter.setUrlWithoutDomain(a.attr("href"))

        return chapter
    }

    // -------------------------------------------------------------
    // Page List
    // -------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()

        return doc.select("img.chapter_img.lazyload").mapIndexedNotNull { index, img ->
            img.attr("data-src")
                .takeIf { it.isNotBlank() }
                ?.let { Page(index, "", it) }
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // -------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------

    override fun getFilterList(): FilterList =
        FilterList(
            GenreFilter(getTags()),
            StateFilter(),
        )

    // Scrapeo de géneros desde el home
    private fun getTags(): List<Tag> {
        return try {
            val doc = client.newCall(GET(baseUrl)).execute().asJsoup()
            doc.select("a.genre-name").map { a ->
                Tag(
                    key = a.attr("href")
                        .substringAfterLast('/')
                        .substringBefore("-comic"),
                    title = a.text(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    class Tag(val key: String, val title: String)

    class GenreFilter(private val tags: List<Tag>) :
        Filter.Select<String>(
            "Genre",
            tags.map { it.title }.toTypedArray(),
        ) {
        val selectedTag: Tag?
            get() = tags.getOrNull(state)
    }

    class StateFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Any", "Ongoing", "Finished"),
        )
}
