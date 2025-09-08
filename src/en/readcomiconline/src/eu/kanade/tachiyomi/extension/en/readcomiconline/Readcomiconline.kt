package eu.kanade.tachiyomi.extension.en.readcomiconline

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Readcomiconline : ParsedHttpSource() {

    override val name = "ReadComicOnline"
    override val baseUrl = "https://readcomiconline.li"
    override val lang = "en"
    override val supportsLatest = true

    // === POPULAR MANGA ===
    override fun popularMangaSelector() = ".list-comic > .item > a:first-child"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.text()
            thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        }
    }

    override fun popularMangaNextPageSelector() = "ul.pager > li > a:contains(Next)"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/ComicList/MostPopular?page=$page", headers)
    }

    // === LATEST UPDATES ===
    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)
    }

    // === SEARCH ===
    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Aquí tu lógica actual de búsqueda
        return GET("$baseUrl/AdvanceSearch?page=$page&comicName=${query.trim()}", headers)
    }

    // === MANGA DETAILS ===
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

        manga.status = infoElement.select("p:has(span:contains(Status:))").first()?.text().orEmpty()
            .let { parseStatus(it) }
        manga.thumbnail_url = document.select(".rightBox:eq(0) img").first()?.absUrl("src")

        return manga
    }

    // === CHAPTERS ===
    override fun chapterListSelector() = "table.listing tr:gt(1)"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()!!
        return SChapter.create().apply {
            setUrlWithoutDomain(urlElement.attr("href"))
            name = urlElement.text()
            date_upload = null // aquí ponés tu lógica de fecha
        }
    }

    // === PAGES ===
    override fun pageListParse(document: Document): List<Page> {
        return emptyList() // reemplazá con tu lógica de páginas
    }

    override fun imageUrlParse(document: Document) = ""
    
    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
}
