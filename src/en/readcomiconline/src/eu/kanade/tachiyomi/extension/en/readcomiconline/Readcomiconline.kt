package eu.kanade.tachiyomi.extension.en.readcomiconline

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Readcomiconline : ParsedHttpSource() {

    override val name = "ReadComicOnline"
    override val baseUrl = "https://readcomiconline.li"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/ComicList/MostPopular?page=$page", headers)

    override fun popularMangaSelector(): String = "div.list-comic div.item a"

    override fun popularMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.attr("title").ifBlank { element.text() }
            thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        }

    override fun popularMangaNextPageSelector(): String = "a.next"

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("div.barContent")!!

        val manga = SManga.create()

        val titleElement = infoElement.selectFirst("a.bigChar")
        val fullTitle = titleElement?.attr("title")?.takeIf { it.isNotBlank() }
            ?: titleElement?.text()?.trim().orEmpty()

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

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector(): String =
        "table.listing tr:has(a)"

    override fun chapterFromElement(element: Element): SChapter =
        SChapter.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
            name = element.selectFirst("a")!!.text()
        }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("select#pageMenu option").mapIndexed { i, el ->
            val url = el.attr("value")
            Page(i, url)
        }
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img#comic_page")!!.attr("abs:src")
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
}
