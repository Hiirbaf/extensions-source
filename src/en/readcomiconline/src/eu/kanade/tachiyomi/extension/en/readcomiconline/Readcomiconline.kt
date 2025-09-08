package eu.kanade.tachiyomi.extension.en.readcomiconline

import eu.kanade.tachiyomi.network.GET
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

class Readcomiconline : ParsedHttpSource() {

    override val name = "ReadComicOnline"
    override val baseUrl = "https://readcomiconline.li"
    override val lang = "en"
    override val supportsLatest = true

    // --------- Popular ---------
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/ComicList/MostPopular?page=$page", headers)

    override fun popularMangaSelector(): String = "table.listing tr:has(a)"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("a")!!
        manga.setUrlWithoutDomain(link.attr("href"))

        // Tomamos primero el atributo "title" porque contiene el texto completo
        val fullTitle = link.attr("title").takeIf { it.isNotBlank() } ?: link.text()
        manga.title = fullTitle.trim()

        return manga
    }

    override fun popularMangaNextPageSelector(): String = "a:contains(Next)"

    // --------- Latest ---------
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // --------- Search ---------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/Search/Comic?keyword=$query&page=$page", headers)

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // --------- Manga details ---------
    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("div.barContent")!!

        val manga = SManga.create()

        val titleElement = infoElement.selectFirst("a.bigChar")
        val fullTitle = titleElement?.attr("title")?.takeIf { it.isNotBlank() }
            ?: titleElement?.text().orEmpty()

        manga.title = fullTitle.trim()
        manga.artist = infoElement.select("p:has(span:contains(Artist:)) > a").first()?.text()
        manga.author = infoElement.select("p:has(span:contains(Writer:)) > a").first()?.text()
        manga.genre = infoElement.select("p:has(span:contains(Genres:)) > *:gt(0)").joinToString { it.text() }
        val descriptionText = infoElement.select("p:has(span:contains(Summary:)) ~ p").text()
        manga.description = "Full title: $fullTitle\n\n$descriptionText"
        manga.status = parseStatus(infoElement.select("p:has(span:contains(Status:))").text())
        manga.thumbnail_url = document.select(".rightBox:eq(0) img").first()?.absUrl("src")

        return manga
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // --------- Chapters ---------
    override fun chapterListSelector(): String = "table.listing tr:has(a)"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val link = element.selectFirst("a")!!

        chapter.setUrlWithoutDomain(link.attr("href"))
        chapter.name = link.text()

        val dateText = element.selectFirst("td:eq(1)")?.text()
        chapter.date_upload = dateText?.let { parseDate(it) } ?: 0L

        return chapter
    }

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)
    private fun parseDate(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // --------- Pages ---------
    override fun pageListParse(document: Document): List<Page> {
        return document.select("select#selectPage option").mapIndexed { index, option ->
            val pageUrl = option.attr("value")
            Page(index, "$baseUrl$pageUrl")
        }
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img.img-responsive")!!.absUrl("src")
    }

    // --------- Filters ---------
    override fun getFilterList(): FilterList = FilterList()
}
