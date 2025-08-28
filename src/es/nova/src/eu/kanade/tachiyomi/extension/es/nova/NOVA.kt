package eu.kanade.tachiyomi.extension.es.nova

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
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
    override fun popularMangaNextPageSelector(): String? = "a.page-numbers.nav-next"

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
    override fun latestUpdatesNextPageSelector(): String? = "a.page-numbers.nav-next"

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
    override fun searchMangaNextPageSelector(): String? = "a.page-numbers.nav-next"

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
        val url = element.attr("href")
        chapter.setUrlWithoutDomain(url.replace(baseUrl, ""))

        val chapterPartName = element.text()

        // --- Obtener volumen automáticamente ---
        // Busca el volumen más cercano como "Volumen X"
        val volume = element.parents()
            .select(".dt-fancy-title")
            .firstOrNull { it.text().startsWith("Volumen") }
            ?.text() ?: ""

        // --- Parseo del capítulo tipo JS ---
        val regex = Regex("""(Parte \d+)[\s\-:.\—]+(.+?):\s*(.+)""")
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
        val article = document.selectFirst("div.txt #article") ?: return emptyList()
        val content = article.clone()

        // Mover imágenes de <noscript> a visibles
        content.select("noscript img").forEach { img ->
            img.parent().before(img)
        }
        content.select("noscript").remove()

        // Limpiar basura
        content.select("script, iframe, .ads, .advertisement, style, ins").remove()

        // Construir HTML final
        val htmlText = """
            <html>
                <head><meta charset="UTF-8"></head>
                <body>${content.html()}</body>
            </html>
        """.trimIndent()

        return listOf(Page(0, "", htmlText))
    }

    override fun imageUrlParse(document: Document): String = ""
}
