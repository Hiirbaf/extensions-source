package eu.kanade.tachiyomi.extension.en.mycomiclist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MyComicList : ParsedHttpSource() {

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
        val url = fixUrl(a.attr("href"))
        val title = element.selectFirst("h3 a")?.text().orEmpty()
        val img = element.selectFirst("img.lazyload")?.attr("data-src")

        return SManga.create().apply {
            setUrlWithoutDomain(toRelative(url))
            this.title = title
            thumbnail_url = img?.let { fixUrl(it) }
        }
    }

    override fun popularMangaNextPageSelector(): String = "a[rel=next]"

    // -------------------------------------------------------------
    // Latest
    // -------------------------------------------------------------
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hot-comic?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // -------------------------------------------------------------
    // Search
    // -------------------------------------------------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var selectedTag: Tag? = null
        var selectedStatus = 0

        filters.forEach { f ->
            when (f) {
                is TagFilter -> selectedTag = f.selected
                is StateFilter -> selectedStatus = f.state
            }
        }

        // 🔴 Prioridad absoluta a búsqueda por texto
        if (query.isNotBlank()) {
            return GET(
                "$baseUrl/comic-search?key=${query.trim()}&page=$page",
                headers,
            )
        }

        when (selectedStatus) {
            1 -> return GET("$baseUrl/ongoing-comic?page=$page", headers)
            2 -> return GET("$baseUrl/completed-comic?page=$page", headers)
        }

        selectedTag?.let {
            return GET("$baseUrl/${it.key}-comic?page=$page", headers)
        }

        return popularMangaRequest(page)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // -------------------------------------------------------------
    // Manga details
    // -------------------------------------------------------------
    override fun mangaDetailsParse(document: Document): SManga {
        val title =
            document.selectFirst("td:contains(Name:) + td strong")?.text()
                ?: document.selectFirst("h1")?.ownText().orEmpty()

        val author = document.selectFirst("td:contains(Author:) + td")?.text()
        val genres = document.select("td:contains(Genres:) + td a").joinToString(", ") { it.text() }

        val statusText =
            document.selectFirst("td:contains(Status:) + td a")?.text()?.lowercase()

        val status = when (statusText) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val desc = document.selectFirst("div.manga-desc p.pdesc")?.text()

        return SManga.create().apply {
            this.title = title
            thumbnail_url = document.selectFirst("div.manga-cover img")?.attr("src")
                ?.let { fixUrl(it) }
            this.author = author
            this.artist = author
            genre = genres
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
        val url = fixUrl(a.attr("href"))
        val name = a.text()

        val dateText = element.selectFirst("span.date, span.time")?.text().orEmpty()

        return SChapter.create().apply {
            setUrlWithoutDomain(toRelative(url))
            this.name = name
            chapter_number = extractChapterNumber(name)
            date_upload = parseDate(dateText)
        }
    }

    private fun extractChapterNumber(text: String): Float {
        return Regex("""\d+(\.\d+)?""")
            .find(text)
            ?.value
            ?.toFloat()
            ?: -1f
    }

    private fun parseDate(text: String): Long {
        if (text.equals("Today", ignoreCase = true)) {
            return System.currentTimeMillis()
        }

        return try {
            SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
                .parse(text.trim())
                ?.time
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // -------------------------------------------------------------
    // Pages
    // -------------------------------------------------------------
    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url + "/all", headers)

    override fun pageListParse(document: Document): List<Page> =
        document.select("img.chapter_img.lazyload")
            .mapIndexedNotNull { index, img ->
                img.attr("data-src")
                    .takeIf { it.isNotBlank() }
                    ?.let { Page(index, "", fixUrl(it)) }
            }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used")

    // -------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------
    override fun getFilterList(): FilterList =
        FilterList(
            TagFilter(STATIC_TAGS),
            StateFilter(),
        )

    private val STATIC_TAGS = listOf(
        Tag("marvel", "Marvel"),
        Tag("dc-comics", "DC Comics"),
        Tag("action", "Action"),
        Tag("adventure", "Adventure"),
        Tag("anthology", "Anthology"),
        Tag("anthropomorphic", "Anthropomorphic"),
        Tag("biography", "Biography"),
        Tag("children", "Children"),
        Tag("comedy", "Comedy"),
        Tag("crime", "Crime"),
        Tag("cyborgs", "Cyborgs"),
        Tag("dark-horse", "Dark Horse"),
        Tag("demons", "Demons"),
        Tag("drama", "Drama"),
        Tag("fantasy", "Fantasy"),
        Tag("family", "Family"),
        Tag("fighting", "Fighting"),
        Tag("gore", "Gore"),
        Tag("graphic-novels", "Graphic Novels"),
        Tag("historical", "Historical"),
        Tag("horror", "Horror"),
        Tag("leading-ladies", "Leading Ladies"),
        Tag("literature", "Literature"),
        Tag("magic", "Magic"),
        Tag("manga", "Manga"),
        Tag("martial-arts", "Martial Arts"),
        Tag("mature", "Mature"),
        Tag("mecha", "Mecha"),
        Tag("military", "Military"),
        Tag("movies-tv", "Movies & TV"),
        Tag("mystery", "Mystery"),
        Tag("mythology", "Mythology"),
        Tag("psychological", "Psychological"),
        Tag("personal", "Personal"),
        Tag("political", "Political"),
        Tag("post-apocalyptic", "Post-Apocalyptic"),
        Tag("pulp", "Pulp"),
        Tag("robots", "Robots"),
        Tag("romance", "Romance"),
        Tag("sci-fi", "Sci-Fi"),
        Tag("slice-of-life", "Slice of Life"),
        Tag("sports", "Sports"),
        Tag("spy", "Spy"),
        Tag("superhero", "Superhero"),
        Tag("supernatural", "Supernatural"),
        Tag("suspense", "Suspense"),
        Tag("thriller", "Thriller"),
        Tag("tragedy", "Tragedy"),
        Tag("vampires", "Vampires"),
        Tag("vertigo", "Vertigo"),
        Tag("video-games", "Video Games"),
        Tag("war", "War"),
        Tag("western", "Western"),
        Tag("zombies", "Zombies"),
    )

    class Tag(val key: String, val title: String)

    class TagFilter(private val tags: List<Tag>) :
        Filter.Select<String>(
            "Genre",
            arrayOf("Any") + tags.map { it.title }.toTypedArray(),
        ) {
        val selected: Tag?
            get() = if (state == 0) null else tags[state - 1]
    }

    class StateFilter :
        Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed"))

    // -------------------------------------------------------------
    // Utils
    // -------------------------------------------------------------
    private fun toRelative(url: String): String =
        if (url.startsWith("http")) url.substringAfter(baseUrl) else url

    private fun fixUrl(url: String): String =
        if (url.startsWith("http")) url else baseUrl + url
}
