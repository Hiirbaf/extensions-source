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
    override val baseUrl = "https://mycomiclist.org"
    override val lang = "en"
    override val supportsLatest = true

    // -------------------------------------------------------------
    // Popular
    // -------------------------------------------------------------
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular-comic?page=$page", headers)

    override fun popularMangaSelector(): String = "div.manga-box"

    override fun popularMangaFromElement(element: Element): SManga {
        val a = element.selectFirst("a")!!
        val rawUrl = a.attr("href")
        val url = fixUrl(rawUrl)
        val title = element.selectFirst("h3 a")?.text().orEmpty()
        val img = element.selectFirst("img.lazyload")?.attr("data-src")

        return SManga.create().apply {
            setUrlWithoutDomain(toRelative(url))
            this.title = title
            thumbnail_url = img
        }
    }

    override fun popularMangaNextPageSelector(): String = "a[rel=next]"

    // -------------------------------------------------------------
    // Latest
    // -------------------------------------------------------------
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hot-comic?page=$page", headers)

    override fun latestUpdatesSelector(): String = "div.manga-box"

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "a[rel=next]"

    // -------------------------------------------------------------
    // Search
    // -------------------------------------------------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()

        return when {
            query.isNotBlank() ->
                GET("$baseUrl/comic-search?key=${query.trim()}&page=$page", headers)

            tagFilter?.selected != null ->
                GET("$baseUrl/${tagFilter.selected!!.key}-comic?page=$page", headers)

            else ->
                popularMangaRequest(page)
        }
    }

    override fun searchMangaSelector(): String = "div.manga-box"

    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = "a[rel=next]"

    // -------------------------------------------------------------
    // Manga details
    // -------------------------------------------------------------
    override fun mangaDetailsParse(document: Document): SManga {
        val realTitle = document.selectFirst("td:contains(Name:) + td strong")?.text()
        val authorText = document.selectFirst("td:contains(Author:) + td")?.text()
        val genres = document.select("td:contains(Genres:) + td a").map { it.text() }
        val statusText = document.selectFirst("td:contains(Status:) + td a")?.text()?.lowercase()
        val status = when (statusText) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        val desc = document.selectFirst("div.manga-desc p.pdesc")?.html()

        return SManga.create().apply {
            title = realTitle ?: document.selectFirst("h1")?.ownText().orEmpty()
            thumbnail_url = document.selectFirst("div.manga-cover img")?.attr("src")
            author = authorText
            artist = authorText
            genre = genres.joinToString(", ")
            description = desc
            this.status = status
        }
    }

    // -------------------------------------------------------------
    // Chapters
    // -------------------------------------------------------------
    override fun chapterListSelector(): String = "ul.basic-list li"

    override fun chapterFromElement(element: Element): SChapter {
        val a = element.selectFirst("a.ch-name")!!

        val rawUrl = a.attr("href")
        val url = fixUrl(rawUrl)
        val name = a.text()

        return SChapter.create().apply {
            setUrlWithoutDomain(toRelative(url))
            this.name = name
            chapter_number = name.substringAfter('#').toFloatOrNull() ?: 0f
        }
    }

    // -------------------------------------------------------------
    // Pages
    // -------------------------------------------------------------
    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.chapter_img.lazyload").mapIndexedNotNull { index, img ->
            val src = img.attr("data-src")
            if (src.isNullOrBlank()) null else Page(index, "", src)
        }
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used")

    // -------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------
    override fun getFilterList(): FilterList {
        val tags = getTags()
        val filters = mutableListOf<Filter<*>>()
        if (tags.isNotEmpty()) filters += TagFilter(tags)
        filters += StateFilter()
        return FilterList(filters)
    }

    private fun getTags(): List<Tag> {
        return try {
            val response = client.newCall(GET(baseUrl, headers)).execute()
            val body = response.body?.string().orEmpty()
            val doc = Jsoup.parse(body)
            doc.select("div.cr-anime-box.genre-box a.genre-name").map { a ->
                Tag(
                    key = a.attr("href").substringAfterLast('/').substringBefore("-comic"),
                    title = a.text()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------
    // Utils & filter classes
    // -------------------------------------------------------------
    private fun toRelative(url: String): String {
        val fixed = url.replace("https//", "https://").replace("http//", "http://")
        return if (fixed.startsWith("http")) fixed.substringAfter(baseUrl) else fixed
    }

    private fun fixUrl(url: String): String {
        val fixed = url.replaceFirst("https//", "https://").replaceFirst("http//", "http://")
        return if (fixed.startsWith("http")) fixed else baseUrl + url
    }

    class Tag(val key: String, val title: String)

    class TagFilter(tags: List<Tag>) :
        Filter.Select<Tag>("Genre", tags.toTypedArray()) {
        val selected: Tag?
            get() = if (state in values.indices) values[state] else null
    }

    class StateFilter :
        Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Finished"))

    // -------------------------------------------------------------
    // ConfigurableSource
    // -------------------------------------------------------------
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // no preferences
    }
}
