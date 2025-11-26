package eu.kanade.tachiyomi.extension.en.mycomiclist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.Jsoup
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
        return SManga.create().apply {
            title = element.selectFirst("h3 a")?.text().orEmpty()
            setUrlWithoutDomain(element.selectFirst("h3 a")?.attr("href").orEmpty())
            thumbnail_url = element.selectFirst("img")?.attr("src")
        }
    }

    override fun popularMangaNextPageSelector() =
        "a.next"

    // -------------------------------------------------------------
    // Latest Updates
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
        var selectedGenre: Tag? = null
        var selectedStatus: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> selectedGenre = filter.selectedTag
                is StateFilter -> selectedStatus = when (filter.state) {
                    1 -> "ongoing"
                    2 -> "finished"
                    else -> null
                }
                else -> {}
            }
        }

        selectedGenre?.let { tag ->
            return GET("$baseUrl/${tag.key}-comic")
        }

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

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.manga-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.manga-cover img")?.attr("src")
            description = document.select("div.manga-right > p").firstOrNull()?.text()

            val authorTag = document.selectFirst("td:contains(Author:) + td")?.text()
            author = authorTag
            artist = authorTag

            genre = document.select("td:contains(Genres:) + td a")
                .joinToString(", ") { it.text() }

            val statusText = document.selectFirst("td:contains(Status:) + td")
                ?.text()
                ?.lowercase()

            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("complete") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // -------------------------------------------------------------
    // Chapters
    // -------------------------------------------------------------

    override fun chapterListSelector() =
        "ul.chapters-list li"

    override fun chapterFromElement(element: Element): SChapter {
        val a = element.selectFirst("a")!!
        return SChapter.create().apply {
            name = a.text()
            setUrlWithoutDomain(a.attr("href"))
        }
    }

    // -------------------------------------------------------------
    // Page List
    // -------------------------------------------------------------

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.chapter_img.lazyload").mapIndexedNotNull { index, img ->
            img.attr("data-src")
                .takeIf { it.isNotBlank() }
                ?.let { url -> Page(index, "", url) }
        }
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used")

    // -------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------

    override fun getFilterList(): FilterList =
        FilterList(
            GenreFilter(getTags()),
            StateFilter(),
        )

    // -------------------------------------------------------------
    // Tags scraping
    // -------------------------------------------------------------

    private fun getTags(): List<Tag> {
        return try {
            val response = client.newCall(GET(baseUrl)).execute()
            val body = response.body?.string().orEmpty()
            val doc = Jsoup.parse(body)

            doc.select("a.genre-name").map { a ->
                Tag(
                    key = a.attr("href")
                        .substringAfterLast('/')
                        .substringBefore("-comic"),
                    title = a.text(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------
    // Filter classes
    // -------------------------------------------------------------

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

    // -------------------------------------------------------------
    // Required by ConfigurableSource
    // -------------------------------------------------------------

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // No preferences
    }
}
