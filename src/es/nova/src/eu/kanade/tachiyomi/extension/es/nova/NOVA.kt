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
    private val searchQuery = "/wp-admin/admin-ajax.php?tags=1&sku=&limit=30&category_results=&order=DESC&category_limit=5&order_by=title&product_thumbnails=1&title=1&excerpt=1&content=&categories=1&attributes=1"

    // --- Utilidad para validar URLs ---
    private fun safeUrl(element: Element): String {
        val href = element.attr("href")?.trim()
        require(!href.isNullOrBlank() && (href.startsWith("http") || href.startsWith("/"))) {
            "Invalid href: ${element.outerHtml()}"
        }
        return href
    }

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
        return if (page > 0) {
            Request.Builder()
                .url("$baseUrl/index.php/page/$page/?s=$query&post_type=product&title=1&excerpt=1&content=0&categories=1&attributes=1&tags=1&sku=0&orderby=popularity&ixwps=1")
                .headers(headers)
                .build()
        } else {
            val body = FormBody.Builder()
                .add("action", "product_search")
                .add("product-search", (page.takeIf { it > 0 } ?: 1).toString())
                .add("product-query", query)
                .build()

            Request.Builder()
                .url(baseUrl + searchQuery)
                .post(body)
                .headers(headers)
                .build()
        }
    }
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = null

    // --- MANGA DETAILS ---
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
        // 1. Sacar el nombre del capítulo (equivalente a los <h2> en el JS)
        val chapterName = document.select("h2")
            .joinToString(" ") { it.text() }
            .ifBlank { "Capítulo sin título" }

        // 2. Obtener el texto del capítulo, según la condición
        val contentElement = if (document.html().contains("Nadie entra sin permiso en la Gran Tumba de Nazarick")) {
            document.selectFirst("#content")
        } else {
            document.selectFirst(".wpb_text_column.wpb_content_element > .wpb_wrapper")
        }

        // 3. Limpiar anuncios
        contentElement?.select("center")?.remove()
        contentElement?.select("*")?.forEach { el ->
            if (el.attr("style").contains("text-align:.center")) {
                el.replaceWith(Element("center").append(el.html()))
            }
        }

        // 4. Construir el "objeto" de salida → en Tachiyomi se simula con Page
        val html = """
            <h2>$chapterName</h2>
            ${contentElement?.html()?.trim().orEmpty()}
        """.trimIndent()

        return listOf(Page(0, document.location(), html))
    }

    override fun imageUrlParse(document: Document): String = ""
}
