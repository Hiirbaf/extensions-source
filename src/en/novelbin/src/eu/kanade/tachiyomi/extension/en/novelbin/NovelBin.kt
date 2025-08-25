package eu.kanade.tachiyomi.extension.en.novelbin

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NovelBin : ParsedHttpSource() {
    override val name = "NovelBin"
    override val baseUrl = "https://novelbin.com"
    override val lang = "en"
    override val supportsLatest = true
    val isNovelSource: Boolean = true

    override fun popularMangaRequest(page: Int): Request =
        Request.Builder().url("$baseUrl/sort/top-hot-novel").headers(headers).build()
    override fun popularMangaSelector(): String = "div > h3.novel-title" // Select the h3 containing the novel title
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val a = element.selectFirst("a")
        manga.setUrlWithoutDomain(a?.attr("href") ?: "")
        manga.title = a?.attr("title") ?: a?.text()?.trim() ?: ""
        // Extract thumbnail robustly from the row
        val row = element.parents().firstOrNull { it.hasClass("row") }
        val coverImg = row?.selectFirst("img.cover") // matches both with and without 'lazy'
        manga.thumbnail_url = coverImg?.absUrl("src").takeIf { !it.isNullOrEmpty() }
            ?: coverImg?.absUrl("data-src")
        // Optionally, extract author if needed
        val author = row?.selectFirst(".author")?.text()?.trim()
        if (!author.isNullOrEmpty()) manga.author = author
        return manga
    }
    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request =
        Request.Builder().url("$baseUrl/sort/latest?page=$page").headers(headers).build()
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // NovelBin uses GET for search with 'keyword' parameter
        return Request.Builder()
            .url("$baseUrl/search?keyword=$query&page=$page")
            .headers(headers)
            .build()
    }
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = null

    override fun mangaDetailsParse(document: org.jsoup.nodes.Document): SManga {
        val manga = SManga.create()
        // Title
        manga.title = document.selectFirst("h1, h2")?.text()?.trim() ?: document.title()
        // Author
        manga.author = document.select("b:contains(Author:)").firstOrNull()?.parent()?.ownText()?.trim()
        // Genre
        manga.genre = document.select("b:contains(Genre:)").firstOrNull()?.parent()?.ownText()?.trim()?.replace(",", ", ")
        // Status
        val statusText = document.select("b:contains(Status:)").firstOrNull()?.parent()?.ownText()?.trim()?.lowercase()
        manga.status = when {
            statusText?.contains("ongoing") == true -> SManga.ONGOING
            statusText?.contains("completed") == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        // Description
        manga.description = document.select("#tab-description, .description, p").firstOrNull()?.text()?.trim()
        // Thumbnail: robust extraction from <img class="cover lazy">, check both src and data-src
        manga.thumbnail_url = document.selectFirst("img.cover.lazy")?.let { img ->
            img.absUrl("src").ifEmpty { img.absUrl("data-src") }
        } ?: document.selectFirst(".book img")?.let { img ->
            img.absUrl("src").ifEmpty { img.absUrl("data-src") }
        } ?: document.selectFirst("meta[itemprop=image]")?.attr("content")
        return manga
    }

    override fun chapterListRequest(manga: SManga): Request {
        // Extract novelId from the manga URL
        val novelId = manga.url.substringAfterLast("/b/")
        val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
        return Request.Builder().url(ajaxUrl).headers(headers).build()
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body)
        return doc.select("ul.list-chapter a").map { chapterFromElement(it) }
    }
    override fun chapterListSelector(): String = "ul.list-chapter a"
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.text().trim()
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        // Extract the main chapter content
        val contentElement = document.selectFirst("#chr-content")
        if (contentElement != null) {
            // Remove ad/script divs
            contentElement.select("div[id^=pf-], script").remove()
            // Preserve <p> and <br> tags by returning HTML as-is
            val content = contentElement.html().trim()
            return listOf(Page(0, document.location(), content))
        }
        // Fallback: return the whole body HTML if #chr-content is missing
        val fallback = document.body().html().trim()
        return listOf(Page(0, document.location(), fallback))
    }
    override fun imageUrlParse(document: Document): String = ""
}
