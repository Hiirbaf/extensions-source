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

    // --- Utilidad para validar URLs ---
    private fun safeUrl(element: Element): String {
        val href = element.attr("href")?.trim()
        require(!href.isNullOrBlank() && (href.startsWith("http") || href.startsWith("/"))) {
            "Invalid href: ${element.outerHtml()}"
        }
        return href
    }

    // --- POPULAR / LATEST ---
    override fun popularMangaRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("action", "product_search")
            .add("product-search", page.toString())
            .add("product-query", "")
            .build()

        return Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php?tags=1&sku=&limit=30&order=DESC&order_by=title")
            .post(body)
            .headers(headers)
            .build()
    }

    override fun popularMangaSelector(): String = "div.wf-cell"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val a = element.selectFirst("h4.entry-title a")
        val img = element.selectFirst("img")
        val url = a?.let { safeUrl(it) } ?: "/"

        manga.setUrlWithoutDomain(url.replace(baseUrl, ""))
        manga.title = a?.text().orEmpty()
        manga.thumbnail_url = img?.attr("data-src") ?: img?.attr("data-cfsrc")

        return manga
    }

    override fun popularMangaNextPageSelector(): String? = null

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

    // --- DETAILS ---
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val coverImg = document.selectFirst(".woocommerce-product-gallery img")

        manga.title = document.selectFirst("h1")?.text().orEmpty()
        manga.thumbnail_url = coverImg?.attr("src") ?: coverImg?.attr("data-cfsrc")
        manga.author = document.select(".woocommerce-product-attributes-item--attribute_pa_escritor td").text()
        manga.artist = document.select(".woocommerce-product-attributes-item--attribute_pa_ilustrador td").text()
        manga.status = when (document.select(".woocommerce-product-attributes-item--attribute_pa_estado td").text().lowercase()) {
            "en curso", "ongoing" -> SManga.ONGOING
            "completado", "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        manga.description = document.select(".woocommerce-product-details__short-description").text()

        return manga
    }

    // --- CHAPTERS ---
    override fun chapterListSelector(): String = ".vc_row div.vc_column-inner > div.wpb_wrapper .wpb_tab a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val url = safeUrl(element)
        chapter.setUrlWithoutDomain(url.replace(baseUrl, ""))
        chapter.name = element.text()
        return chapter
    }

    // --- CHAPTER TEXT ---
    override fun pageListParse(document: Document): List<Page> {
        val content = document.selectFirst("#content")
            ?: document.selectFirst(".wpb_text_column.wpb_content_element > .wpb_wrapper")

        // limpiar ads
        content?.select("center")?.remove()
        val html = content?.html()?.trim().orEmpty()

        // 👇 aquí usamos `text` en lugar de meter el HTML en imageUrl
        return listOf(Page(index = 0, url = document.location(), text = html))
    }

    override fun imageUrlParse(document: Document): String = ""
}
