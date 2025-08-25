package eu.kanade.tachiyomi.extension.es.nova

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NOVA : ParsedHttpSource() {

    override val name = "NOVA"
    override val baseUrl = "https://novelasligeras.net"
    override val lang = "es"
    override val supportsLatest = true
    private val searchQuery = "/wp-admin/admin-ajax.php?tags=1&sku=&limit=30&category_results=&order=DESC&category_limit=5&order_by=title&product_thumbnails=1&title=1&excerpt=1&content=&categories=1&attributes=1"

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
    override fun chapterListSelector(): String = ".vc_row div.vc_column-inner > div.wpb_wrapper"
    override fun chapterFromElement(element: Element): SChapter {
        val volume = element.selectFirst(".dt-fancy-title")?.text().orEmpty()
        val a = element.select(".wpb_tab a")

        val chapters = mutableListOf<SChapter>()
        a.forEach { link ->
            val chapterPartName = link.text()
            val chapterUrl = link.attr("href").replace(baseUrl, "")

            val regex = Regex("(Parte \\d+) . (.+?): (.+)")
            val match = regex.find(chapterPartName)

            val part = match?.groupValues?.getOrNull(1)
            val chapter = match?.groupValues?.getOrNull(2)
            val name = match?.groupValues?.getOrNull(3)

            val chapterName = if (part != null && chapter != null) {
                "$volume - $chapter - $part: $name"
            } else {
                "$volume - $chapterPartName"
            }

            val ch = SChapter.create()
            ch.name = chapterName
            ch.setUrlWithoutDomain(chapterUrl)
            chapters.add(ch)
        }

        // Devuelvo el primero, porque ParsedHttpSource espera 1 solo acá
        return chapters.firstOrNull() ?: SChapter.create()
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body)
        val result = mutableListOf<SChapter>()
        doc.select(chapterListSelector()).forEach { wrapper ->
            val volume = wrapper.selectFirst(".dt-fancy-title")?.text().orEmpty()
            if (volume.startsWith("Volumen")) {
                wrapper.select(".wpb_tab a").forEach { link ->
                    val chapter = chapterFromElement(wrapper) // reutilizo la lógica
                    chapter.url = link.attr("href").replace(baseUrl, "")
                    result.add(chapter)
                }
            }
        }
        return result
    }

    // --- CHAPTER TEXT ---
    override fun pageListParse(document: Document): List<Page> {
        var contentElement: Element? = null

        if (document.html().contains("Nadie entra sin permiso en la Gran Tumba de Nazarick")) {
            contentElement = document.selectFirst("#content")
        } else {
            contentElement = document.selectFirst(".wpb_text_column.wpb_content_element > .wpb_wrapper")
        }

        // Remover anuncios
        contentElement?.select("center")?.remove()
        contentElement?.select("*")?.forEach { el ->
            if (el.attr("style").contains("text-align:.center")) {
                el.replaceWith(Element("center").append(el.html()))
            }
        }

        val html = contentElement?.html()?.trim().orEmpty()
        return listOf(Page(0, document.location(), html))
    }

    override fun imageUrlParse(document: Document): String = ""
}
