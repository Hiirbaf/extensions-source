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
        val url = "$baseUrl/index.php/page/$page/?post_type=product&orderby=popularity"
        return Request.Builder()
            .url(url)
            .headers(headers)
            .build()
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
    override fun popularMangaNextPageSelector(): String? = "a.next"

    // --- LATEST UPDATES ---
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/index.php/page/$page/?post_type=product&orderby=date"
        return Request.Builder()
            .url(url)
            .headers(headers)
            .build()
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = "a.next"

    // --- SEARCH ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/index.php/page/$page/?s=$encodedQuery&post_type=product&orderby=relevance"
        return Request.Builder()
            .url(url)
            .headers(headers)
            .build()
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = "a.next"

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

        val chapterPartName = element.text()

        // --- Obtener volumen automáticamente ---
        // Busca el volumen más cercano como "Volumen X"
        val volume = element.parents()
            .select(".dt-fancy-title")
            .firstOrNull { it.text().startsWith("Volumen") }
            ?.text() ?: ""

        // --- Parseo del capítulo tipo JS ---
        val regex = Regex("""(Parte \d+) . (.+?): (.+)""")
        val matchResult = regex.find(chapterPartName)

        val chapterName = if (matchResult != null && matchResult.groupValues.size >= 4) {
            val part = matchResult.groupValues[1]
            val chapterNum = matchResult.groupValues[2]
            val name = matchResult.groupValues[3]
            if (volume.isNotBlank()) "$volume - $chapterNum - $part: $name" else "$chapterNum - $part: $name"
        } else {
            if (volume.isNotBlank()) "$volume - $chapterPartName" else chapterPartName
        }

        chapter.name = chapterName
        return chapter
    }

    // --- CHAPTER TEXT ---
    override fun pageListParse(document: Document): List<Page> {
        // Detectar si se debe usar #content o el wrapper
        val contentElement = if (document.html().contains("Nadie entra sin permiso en la Gran Tumba de Nazarick")) {
            document.selectFirst("#content")
        } else {
            document.selectFirst(".wpb_text_column.wpb_content_element > .wpb_wrapper")
        }

        // Quitar el título de la novela dentro del contenido
        contentElement?.select("h1")?.firstOrNull()?.remove()

        // --- Limpieza de anuncios y basura ---
        contentElement?.select("center")?.remove()

        // Normalizar centrados
        contentElement?.select("*")?.forEach { el ->
            if (el.attr("style").contains("text-align:.center")) {
                el.removeAttr("style")
                el.tagName("div").attr("align", "center")
            }
        }

        // Quitar imágenes duplicadas en <noscript>
        contentElement?.select("noscript")?.remove()

        // Quitar párrafos vacíos o con solo &nbsp;
        contentElement?.select("p")?.forEach { el ->
            if (el.text().isBlank()) {
                el.remove()
            }
        }

        // Quitar <br> innecesarios o consecutivos
        contentElement?.select("br")?.forEach { br ->
            if (br.nextElementSibling() == null || br.nextElementSibling()?.tagName() == "br") {
                br.remove()
            }
        }

        // --- Construir el HTML final (solo contenido limpio) ---
        val html = contentElement?.html()?.trim().orEmpty()

        return listOf(Page(0, document.location(), html))
    }
    override fun imageUrlParse(document: Document): String = ""
}
