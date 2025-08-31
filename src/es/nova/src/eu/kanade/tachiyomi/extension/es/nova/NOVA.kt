package eu.kanade.tachiyomi.extension.es.nova

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
import java.net.URLEncoder

class NOVA : ParsedHttpSource() {

    override val name = "NOVA"
    override val baseUrl = "https://novelasligeras.net"
    override val lang = "es"
    override val supportsLatest = true
    val isNovelSource: Boolean = true

    private companion object {
        private const val NEXT_PAGE_SELECTOR = "a.page-numbers.nav-next"
        private const val ITEM_SELECTOR = "div.wf-cell"
        private val CHAPTER_REGEX = Regex("""(Parte \d+)[\s\-:.\–]+(.+?):\s*(.+)""")
    }

    private fun GET(url: String) = Request.Builder().url(url).headers(headers).build()

    // --- HELPERS ---
    private fun Element.extractThumbnail(): String? =
        attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

    private fun Document.detail(selector: String) = selectFirst(selector)?.text()?.takeIf { it.isNotBlank() }

    private fun Document.textOrEmpty(selector: String) = detail(selector).orEmpty()

    private fun parseMangaElement(element: Element) = SManga.create().apply {
        val img = element.selectFirst("img")
        val link = element.selectFirst("h4.entry-title a")
        setUrlWithoutDomain(link?.attr("href")?.removePrefix(baseUrl).orEmpty())
        title = link?.text().orEmpty()
        thumbnail_url = img?.extractThumbnail()
    }

    // --- POPULAR / LATEST / SEARCH ---
    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/index.php/page/$page/?post_type=product&orderby=popularity")
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/index.php/page/$page/?post_type=product&orderby=date")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/index.php/page/$page/?s=$q&post_type=product&orderby=relevance")
    }

    override fun popularMangaSelector() = ITEM_SELECTOR
    override fun latestUpdatesSelector() = ITEM_SELECTOR
    override fun searchMangaSelector() = ITEM_SELECTOR

    override fun popularMangaFromElement(element: Element) = parseMangaElement(element)
    override fun latestUpdatesFromElement(element: Element) = parseMangaElement(element)
    override fun searchMangaFromElement(element: Element) = parseMangaElement(element)

    override fun popularMangaNextPageSelector() = NEXT_PAGE_SELECTOR
    override fun latestUpdatesNextPageSelector() = NEXT_PAGE_SELECTOR
    override fun searchMangaNextPageSelector() = NEXT_PAGE_SELECTOR

    // --- MANGA DETAILS ---
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val product = document.selectFirst("div.product.type-product[id^=product-]")
        val coverImg = document.selectFirst(".woocommerce-product-gallery img")
        val labels = product
            ?.select(".woocommerce-product-gallery .berocket_better_labels b")
            ?.eachText()
            ?.map { it.trim() }
            ?.distinct()
            ?.take(2) ?: emptyList()
        val genres = document.select(".product_meta .posted_in a").eachText().map { it.trim() }

        title = document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = coverImg?.extractThumbnail()
        author = document.textOrEmpty(".woocommerce-product-attributes-item--attribute_pa_escritor td")
        artist = document.textOrEmpty(".woocommerce-product-attributes-item--attribute_pa_ilustrador td")
        description = (labels.joinToString(" ") { "[$it]" } + "\n\n" + document.select(".woocommerce-product-details__short-description").text()).trim()
        genre = genres.joinToString(", ")
        status = when (document.textOrEmpty(".woocommerce-product-attributes-item--attribute_pa_estado td").lowercase()) {
            "en curso", "ongoing" -> SManga.ONGOING
            "completado", "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // --- CHAPTERS ---
    override fun chapterListSelector() = ".vc_row div.vc_column-inner > div.wpb_wrapper .wpb_tab a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href").removePrefix(baseUrl))

        val chapterText = element.text()
        val volume = element.parents().select(".dt-fancy-title").firstOrNull { it.text().startsWith("Volumen") }?.text().orEmpty()

        name = CHAPTER_REGEX.find(chapterText)?.let { m ->
            val (part, number, title) = m.destructured
            buildString {
                if (volume.isNotBlank()) append("$volume - ")
                append("$number - $part: $title")
            }
        } ?: buildString {
            if (volume.isNotBlank()) append("$volume - ")
            append(chapterText)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        response.body?.use { body ->
            val doc = Jsoup.parse(body.string())
            return doc.select(chapterListSelector()).map { chapterFromElement(it) }.reversed()
        }
        return emptyList()
    }

    // --- CHAPTER TEXT ---
    override fun pageListParse(document: Document): List<Page> {
        val contentElement = document.selectFirst(
            if (document.html().contains("Nadie entra sin permiso en la Gran Tumba de Nazarick"))
            "#content"
            else
            ".wpb_text_column.wpb_content_element > .wpb_wrapper"
        )

        contentElement?.select("h1, center, img.aligncenter.size-large")?.remove()
        val content = contentElement?.html()?.trim() ?: document.body().html().trim()

        return listOf(Page(0, document.location(), content))
    }

    override fun imageUrlParse(document: Document) = ""
}
