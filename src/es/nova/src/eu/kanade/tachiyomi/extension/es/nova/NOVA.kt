package eu.kanade.tachiyomi.extension.es.nova

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NOVA : ParsedHttpSource() {
    override val name = "NOVA"
    override val baseUrl = "https://novelasligeras.net"
    override val lang = "es"
    override val supportsLatest = true
    val isNovelSource: Boolean = true

    // --- POPULAR NOVELS ---
    override fun popularMangaRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList())
    }
    override fun popularMangaSelector(): String = "div.wf-cell"
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val img = element.selectFirst("img")
        val a = element.selectFirst("h4.entry-title a")
        manga.setUrlWithoutDomain(a?.attr("href")?.replace(baseUrl, "") ?: "")
        manga.title = a?.text().orEmpty()
        manga.thumbnail_url = img?.attr("data-src") ?: img?.attr("data-cfsrc")
        return manga
    }
    override fun popularMangaNextPageSelector(): String? = null

    // --- LATEST UPDATES ---
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = null

    // --- SEARCH ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = FormBody.Builder()
            .add("action", "product_search")
            .add("product-search", page.toString())
            .add("product-query", query)
            .build()
        return Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php?tags=1&sku=&limit=30&order=DESC&order_by=title")
            .post(body)
            .headers(headers)
            .build()
    }
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = null
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val ogAuthor = document.selectFirst("meta[property=og:novel:author]")?.attr("content")
        val ogGenre = document.selectFirst("meta[property=og:novel:genre]")?.attr("content")
        val ogStatus = document.selectFirst("meta[property=og:novel:status]")?.attr("content")
        val ogDesc = document.selectFirst("meta[property=og:description]")?.attr("content")
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")

        val descElement = document.selectFirst(".txt .inner")
        val fullDesc = descElement?.select("p")?.joinToString("\n") { it.text() }?.takeIf { it.isNotBlank() } ?: ogDesc

        manga.title = ogTitle ?: document.title()
        manga.author = ogAuthor
        manga.genre = ogGenre
        manga.status = when (ogStatus?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        manga.description = document.select(".woocommerce-product-details__short-description").text()
        manga.thumbnail_url = ogImage
        return manga
    }

    override fun chapterListSelector(): String = ".vc_row div.vc_column-inner > div.wpb_wrapper .wpb_tab a"
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.text()
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        val article = document.selectFirst("div.txt #article")
        val content = article?.html() ?: ""
        return listOf(Page(0, document.location(), content))
    }
    override fun imageUrlParse(document: Document): String = ""
}
