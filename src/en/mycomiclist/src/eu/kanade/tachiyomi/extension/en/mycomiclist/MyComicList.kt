package eu.kanade.tachiyomi.extension.en.mycomiclist

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MyComicList : HttpSource() {

    override val name = "MyComicList"
    override val baseUrl = "https://mycomiclist.org"
    override val lang = "en"
    override val supportsLatest = true

    // ----- LISTA -----

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular-comic?page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hot-comic?page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()
        val stateFilter = filters.filterIsInstance<StateFilter>().firstOrNull()

        return when {
            query.isNotBlank() ->
                GET("$baseUrl/comic-search?key=${query.trim()}&page=$page", headers)

            tagFilter?.selected != null ->
                GET("$baseUrl/${tagFilter.selected!!.key}-comic?page=$page", headers)

            else ->
                popularMangaRequest(page)
        }
    }

    override fun popularMangaParse(response: Response) = parseList(response)
    override fun latestUpdatesParse(response: Response) = parseList(response)
    override fun searchMangaParse(response: Response) = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select("div.manga-box").map { div ->
            val a = div.selectFirst("a")!!
            val url = a.attr("href")
            val title = div.selectFirst("h3 a")?.text().orEmpty()
            val img = div.selectFirst("img.lazyload")?.attr("data-src")

            SManga.create().apply {
                this.url = fixUrl(url)
                this.title = title
                this.thumbnail_url = img
                this.author = null
                this.artist = null
            }
        }

        val hasNext = doc.select("a[rel=next]").isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    private fun fixUrl(href: String): String {
        var url = href.trim()

        // Si ya empieza con http correctamente → lo devolvemos
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }

        // Si viene sin ":" → lo arreglamos
        if (url.startsWith("https//")) {
            url = url.replaceFirst("https//", "https://")
        } else {
            if (url.startsWith("http//")) {
            url = url.replaceFirst("http//", "http://")
            }
        }

        // Si sigue sin ser absoluta → la unimos al baseUrl
        return if (url.startsWith("http")) url else baseUrl + url
    }

    // ----- DETALLES -----

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        val authorText = doc.selectFirst("td:contains(Author:) + td")?.text()

        val genres = doc.select("td:contains(Genres:) + td a").map { a ->
            a.text()
        }

        val statusText = doc.selectFirst("td:contains(Status:) + td a")
            ?.text()
            ?.lowercase()

        val status = when (statusText) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val desc = doc.selectFirst("div.manga-desc p.pdesc")?.html()

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("div.manga-cover img")?.attr("src")
            author = authorText
            artist = authorText
            genre = genres.joinToString(", ")
            description = desc
            this.status = status
        }
    }

    // ----- CAPÍTULOS -----

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()

        return doc.select("ul.basic-list li").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a.ch-name") ?: return@mapIndexedNotNull null
            val url = a.attr("href")
            val name = a.text()

            SChapter.create().apply {
                this.url = fixUrl(url)
                this.name = name
                chapter_number = name.substringAfter('#').toFloatOrNull() ?: (i + 1f)
            }
        }
    }

    // ----- PÁGINAS -----

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url + "/all", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()

        return doc.select("img.chapter_img.lazyload").mapIndexedNotNull { index, img ->
            val src = img.attr("data-src")
            if (src.isNullOrBlank()) {
                null
            } else {
                Page(index, "", src)
            }
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // ----- FILTROS -----

    override fun getFilterList(): FilterList =
        FilterList(
            TagFilter(getTags()),
            StateFilter(),
        )

    // Filtro de tags
    private fun getTags(): List<Tag> {
        return try {
            val doc = client.newCall(GET(baseUrl)).execute().asJsoup()
            doc.select("div.cr-anime-box.genre-box a.genre-name").map { a ->
                Tag(
                    key = a.attr("href").substringAfterLast('/').substringBefore("-comic"),
                    title = a.text(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    class Tag(val key: String, val title: String)
    class TagFilter(tags: List<Tag>) :
        Filter.Select<Tag>("Genre", tags.toTypedArray()) {
        val selected get() = if (state >= 0 && state < values.size) values[state] else null
    }

    class StateFilter :
        Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Finished"))

    // Utilidad
    private fun Response.asJsoup(): Document = Jsoup.parse(body.string())
}
