package eu.kanade.tachiyomi.extension.es.nova

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class NOVA : ParsedHttpSource() {

    override val name = "NOVA"
    override val baseUrl = "https://novelasligeras.net"
    override val lang = "es"
    override val supportsLatest = true
    val isNovelSource: Boolean = true

    private fun GET(url: String): Request =
        Request.Builder().url(url).headers(headers).build()

    private companion object {
        private const val NEXT_PAGE_SELECTOR = "a.page-numbers.nav-next"
    }

    // --- POPULAR NOVELS ---
    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/index.php/page/$page/?post_type=product&orderby=popularity")

    override fun popularMangaSelector(): String = "div.wf-cell"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val img = element.selectFirst("img")
        val a = element.selectFirst("h4.entry-title a")
        setUrlWithoutDomain(a?.attr("href")?.removePrefix(baseUrl).orEmpty())
        title = a?.text().orEmpty()
        thumbnail_url = img?.attr("data-src") ?: img?.attr("data-cfsrc")
    }

    override fun popularMangaNextPageSelector(): String? = NEXT_PAGE_SELECTOR

    // --- LATEST UPDATES ---
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/index.php/page/$page/?post_type=product&orderby=date")

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = NEXT_PAGE_SELECTOR

    // --- SEARCH ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/index.php/page/$page/?s=$encodedQuery&post_type=product&orderby=relevance")
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = NEXT_PAGE_SELECTOR

    // --- MANGA DETAILS ---
    private fun Document.detail(selector: String): String? =
        select(selector).text().takeIf { it.isNotBlank() }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val coverImg = document.selectFirst(".woocommerce-product-gallery img")

        title = document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = coverImg?.attr("src") ?: coverImg?.attr("data-cfsrc")
        author = document.detail(".woocommerce-product-attributes-item--attribute_pa_escritor td")
        artist = document.detail(".woocommerce-product-attributes-item--attribute_pa_ilustrador td")
        description = document.select(".woocommerce-product-details__short-description").text()
        val labels = document.selectFirst("div.berocket_better_labels")?.select("> b")?.map { it.text().trim() }?.joinToString(", ").orEmpty()
        val genres = document.select(".product_meta .posted_in a").map { it.text().trim() }
        genre = (labels + genres).joinToString(", ")
        status = when (document.detail(".woocommerce-product-attributes-item--attribute_pa_estado td")?.lowercase()) {
            "en curso", "ongoing" -> SManga.ONGOING
            "completado", "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // --- CHAPTERS ---
    override fun chapterListSelector(): String =
        ".vc_row div.vc_column-inner > div.wpb_wrapper .wpb_tab a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href").removePrefix(baseUrl))

        val chapterPartName = element.text()
        val volume = element.parents()
            .select(".dt-fancy-title")
            .firstOrNull { it.text().startsWith("Volumen") }
            ?.text().orEmpty()

        val regex = Regex("""(Parte \d+)[\s\-:.\–]+(.+?):\s*(.+)""")
        name = regex.find(chapterPartName)?.let { m ->
            val part = m.groupValues[1]
            val chapterNum = m.groupValues[2]
            val title = m.groupValues[3]
            buildString {
                if (volume.isNotBlank()) append("$volume - ")
                append("$chapterNum - $part: $title")
            }
        } ?: buildString {
            if (volume.isNotBlank()) append("$volume - ")
            append(chapterPartName)
        }
    }

    // --- CHAPTER TEXT ---
    override fun pageListParse(document: Document): List<Page> {
        val contentElement = when {
            document.html().contains("Nadie entra sin permiso en la Gran Tumba de Nazarick") ->
                document.selectFirst("#content")
            else -> document.selectFirst(".wpb_text_column.wpb_content_element > .wpb_wrapper")
        }

        val content = contentElement?.apply {
            select("h1, center, img.aligncenter.size-large").remove()
        }?.html()?.trim() ?: document.body().html().trim()

        return listOf(Page(0, document.location(), content))
    }

    override fun imageUrlParse(document: Document): String = ""
}
