package eu.kanade.tachiyomi.extension.en.xoxocomics

import eu.kanade.tachiyomi.multisrc.wpcomics.WPComics
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class XoxoComics : WPComics(
    "XOXO Comics",
    "https://xoxocomic.com",
    "en",
    dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US),
    gmtOffset = null,
) {
    override val searchPath = "search-comic"
    override val popularPath = "hot-comic"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comic-update?page=$page", headers)
    override fun latestUpdatesSelector() = "li.row"
    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("h3 a").let {
                title = it.text()
                setUrlWithoutDomain(it.attr("href"))
            }
            thumbnail_url = element.select("img").attr("data-original")
        }
    }

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        return FilterList(
            Filter.Header("Search query ignores Genre/Status filter"),
            StatusFilter("Status", getStatusList()),
            if (genreList.isEmpty()) {
                Filter.Header("Tap 'Reset' to load genres")
            } else {
                GenreFilter("Genre", genreList)
            },
        )
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Obtener filtros seleccionados
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()

        // Si hay query, hacemos búsqueda por texto
        if (query.isNotEmpty()) {
            val url = "$baseUrl/$searchPath?keyword=$query&page=$page"
            return GET(url, headers)
        }

        // Construir URL según filtros
        val urlBuilder = StringBuilder(baseUrl)
        when {
            genreFilter != null && statusFilter != null -> {
                // Género + status: https://xoxocomic.com/{genre}-comic/{status}
                urlBuilder.append("/${genreFilter.toUriPart()}-comic/${statusFilter.toUriPart()}")
            }
            genreFilter != null -> {
                // Solo género: https://xoxocomic.com/{genre}-comic
                urlBuilder.append("/${genreFilter.toUriPart()}-comic")
            }
            statusFilter != null -> {
                // Solo status: https://xoxocomic.com/comic/{status}
                urlBuilder.append("/comic/${statusFilter.toUriPart()}")
            }
        }
        urlBuilder.append("?page=$page&sort=0")
        return GET(urlBuilder.toString(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        fun parseChapters(doc: org.jsoup.nodes.Document) {
            doc.select(chapterListSelector()).map { chapters.add(chapterFromElement(it)) }
            doc.select("ul.pagination a[rel=next]").firstOrNull()?.let { next ->
                parseChapters(client.newCall(GET(next.attr("abs:href"), headers)).execute().asJsoup())
            }
        }
        parseChapters(response.asJsoup())
        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        return super.chapterFromElement(element).apply {
            date_upload = element.select("div.col-xs-3").text().toDate()
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + "${chapter.url}/all", headers)
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!! + "#imagereq", headers)

    override fun genresRequest() = GET("$baseUrl/comic-list", headers)
    override val genresSelector = ".genres h2:contains(Genres) + ul.nav li a"
}
