package eu.kanade.tachiyomi.extension.en.aurora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Aurora : HttpSource() {
    override val name = "Comix.to"
    override val baseUrl = "https://comix.to"
    override val lang = "en"
    override val supportsLatest = true

    // --- Popular (browse) ---
    override fun popularMangaRequest(page: Int): Request {
        // /browser es la ruta de listado; si quieres filtrar por page se puede añadir ?page=
        val url = "$baseUrl/browser?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val mangas = mutableListOf<SManga>()

        // Intentar extraer objetos desde __NEXT_DATA__ o un script con sharedData
        val json = extractNextDataJson(doc)
        if (json != null) {
            // intentar localizar una lista de items en props.pageProps
            val list = extractListFromNextJson(json)
            if (list != null) {
                list.forEach { obj ->
                    val m = SManga.create()
                    m.title = obj.optString("title", obj.optString("name", "Unknown"))
                    m.thumbnail_url = obj.optString("cover", null)
                    val slug = obj.optString("slug", obj.optString("id", ""))
                    m.url = if (slug.startsWith("/")) slug else "/title/$slug"
                    mangas.add(m)
                }
                return MangasPage(mangas, false)
            }
        }

        // Fallback HTML parse: tarjetas de la página de browser
        doc.select(".card, .comic-card, .comic .item").forEach { el ->
            val m = SManga.create()
            m.title = el.selectFirst(".title, .card-title, h3")?.text() ?: ""
            m.thumbnail_url = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            val href = el.selectFirst("a")?.attr("href") ?: ""
            m.url = if (href.startsWith("/")) href else "/$href"
            mangas.add(m)
        }
        return MangasPage(mangas, false)
    }

    // --- Search ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "utf-8")
        val url = "$baseUrl/search?q=$q&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val mangas = mutableListOf<SManga>()

        val json = extractNextDataJson(doc)
        val list = if (json != null) extractListFromNextJson(json) else null
        if (list != null) {
            list.forEach { obj ->
                val m = SManga.create()
                m.title = obj.optString("title", obj.optString("name", ""))
                m.thumbnail_url = obj.optString("cover", null)
                val slug = obj.optString("slug", "")
                m.url = if (slug.startsWith("/")) slug else "/title/$slug"
                mangas.add(m)
            }
            return MangasPage(mangas, false)
        }

        // Fallback HTML
        doc.select(".search-result .card, .card, .comic-card").forEach { el ->
            val m = SManga.create()
            m.title = el.selectFirst(".title, h3")?.text() ?: ""
            m.thumbnail_url = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            val href = el.selectFirst("a")?.attr("href") ?: ""
            m.url = if (href.startsWith("/")) href else "/$href"
            mangas.add(m)
        }
        return MangasPage(mangas, false)
    }

    // --- Details ---
    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("/")) baseUrl + manga.url else manga.url
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val s = SManga.create()

        val json = extractNextDataJson(doc)
        if (json != null) {
            val md = extractMangaObjectFromNextJson(json, doc.location())
            if (md != null) {
                s.title = md.optString("title", s.title)
                s.description = md.optString("description", doc.selectFirst(".summary, .desc")?.text() ?: "")
                s.author = md.optString("author", doc.selectFirst(".author")?.text())
                val genres = mutableListOf<String>()
                val arr = md.optJSONArray("genres")
                if (arr != null) {
                    for (i in 0 until arr.length()) genres.add(arr.optString(i))
                } else {
                    doc.select(".genres a").forEach { genres.add(it.text()) }
                }
                s.genre = genres.joinToString(", ")
                s.thumbnail_url = md.optString("cover", s.thumbnail_url)
                s.status = SManga.UNKNOWN
                return s
            }
        }

        // Fallback HTML
        s.title = doc.selectFirst("h1, .manga-title")?.text() ?: s.title
        s.description = doc.selectFirst(".summary, .desc")?.text() ?: ""
        s.author = doc.selectFirst(".author")?.text()
        s.thumbnail_url = doc.selectFirst(".cover img")?.attr("src")
        s.genre = doc.select(".genres a").joinToString(", ") { it.text() }
        s.status = SManga.UNKNOWN
        return s
    }

    // --- Chapters ---
    override fun chapterListRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("/")) baseUrl + manga.url else manga.url
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val chapters = mutableListOf<SChapter>()

        // Prefer JSON embedded
        val json = extractNextDataJson(doc)
        if (json != null) {
            val chList = extractChaptersFromNextJson(json)
            if (chList != null) {
                chList.forEach { obj ->
                    val c = SChapter.create()
                    c.name = obj.optString("title", obj.optString("name", "Chapter"))
                    val slug = obj.optString("slug", obj.optString("id", ""))
                    c.url = if (slug.startsWith("/")) slug else "/title/${extractSlugFromDocLocation(doc.location())}/$slug"
                    c.date_upload = obj.optLong("timestamp", 0L)
                    chapters.add(c)
                }
                return chapters
            }
        }

        // Fallback HTML: buscar dentro del reader/selector de capítulos
        // En tu URL de capítulo se ve una lista tipo "Ch 43", asumimos enlaces dentro de nav/ul
        doc.select(".chapters a, .chapter-list a, .chapters li a, .chapter-item a").forEach { el ->
            val c = SChapter.create()
            c.name = el.text().trim()
            val href = el.attr("href")
            c.url = if (href.startsWith("/")) href else "/$href"
            chapters.add(c)
        }
        return chapters
    }

    // --- Pages ---
    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("/")) baseUrl + chapter.url else chapter.url
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val pages = mutableListOf<Page>()

        // Intentar extraer imágenes desde JSON embebido (common in Next.js readers)
        val json = extractNextDataJson(doc)
        val imgsFromJson = extractImageListFromNextJson(json)
        if (imgsFromJson != null && imgsFromJson.isNotEmpty()) {
            imgsFromJson.forEachIndexed { i, url -> pages.add(Page(i, "", url)) }
            return pages
        }

        // Fallback HTML: buscar imgs dentro del reader
        // comix.to suele cargar <img> dentro del viewer. Probamos varios selectores comunes.
        val imgEls = doc.select(".reader img, .page img, .viewer img, .comic-page img")
        imgEls.forEachIndexed { i, el ->
            val src = el.attr("data-src").ifEmpty { el.attr("src") }
            if (src.isNotBlank()) pages.add(Page(i, "", src))
        }

        // Si no hay imgs directos, comprobar <script> que contenga un array de URLs
        if (pages.isEmpty()) {
            val scriptWithImgs = doc.select("script").firstOrNull { it.data().contains("images") || it.data().contains("pages") }
            scriptWithImgs?.let {
                val text = it.data()
                // intentar extraer URLs con regexp básica
                val regex = """https?://[^\s'"]+\.(?:jpg|jpeg|png|webp)""".toRegex()
                val matches = regex.findAll(text).map { m -> m.value }.toList()
                matches.forEachIndexed { i, u -> pages.add(Page(i, "", u)) }
            }
        }

        return pages
    }

    // --- Helpers ---

    // Extrae JSON grande desde scripts tipo __NEXT_DATA__ o window.__NEXT_DATA__
    private fun extractNextDataJson(doc: Document): JSONObject? {
        try {
            val script = doc.selectFirst("script#__NEXT_DATA__, script:containsData(__NEXT_DATA__), script:containsData(sharedData)")
            if (script != null) {
                val data = script.data()
                val start = data.indexOf('{')
                val end = data.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    val jsonText = data.substring(start, end + 1)
                    return JSONObject(jsonText)
                }
            }
            // fallback: buscar cualquier script con "window.__NEXT_DATA__"
            doc.select("script").forEach { s ->
                val t = s.data()
                if (t.contains("window.__NEXT_DATA__") || t.contains("__NEXT_DATA__")) {
                    val idx = t.indexOf('{')
                    val idy = t.lastIndexOf('}')
                    if (idx >= 0 && idy > idx) return JSONObject(t.substring(idx, idy + 1))
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return null
    }

    private fun extractListFromNextJson(json: JSONObject): List<JSONObject>? {
        try {
            // Estructura común: props.pageProps.someKey.items o props.pageProps.sharedData
            val props = json.optJSONObject("props") ?: return null
            val pageProps = props.optJSONObject("pageProps") ?: return null

            // Búsquedas típicas
            val candidates = listOf("items", "mangas", "data", "results", "titles")
            for (c in candidates) {
                if (pageProps.has(c)) {
                    val arr = pageProps.optJSONArray(c) ?: continue
                    val out = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                    if (out.isNotEmpty()) return out
                }
            }

            // buscar recursivamente (simple)
            val shared = pageProps.optJSONObject("sharedData") ?: pageProps.optJSONObject("initialData")
            if (shared != null) {
                val arr = shared.optJSONArray("items")
                if (arr != null) {
                    val out = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                    if (out.isNotEmpty()) return out
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractMangaObjectFromNextJson(json: JSONObject, location: String): JSONObject? {
        try {
            val props = json.optJSONObject("props") ?: return null
            val pageProps = props.optJSONObject("pageProps") ?: return null

            // A menudo la página de título incluye "manga" o "title" dentro de pageProps
            val keys = listOf("manga", "title", "item", "data")
            for (k in keys) {
                val o = pageProps.optJSONObject(k) ?: continue
                if (o.has("slug") || o.has("title")) return o
            }

            // fallback: si hay sharedData con objetos, devolver primer objeto con "title"
            val shared = pageProps.optJSONObject("sharedData")
            shared?.let {
                shared.keys().forEach { key ->
                    val o = shared.optJSONObject(key)
                    if (o != null && o.has("title")) return o
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractChaptersFromNextJson(json: JSONObject): List<JSONObject>? {
        try {
            val props = json.optJSONObject("props") ?: return null
            val pageProps = props.optJSONObject("pageProps") ?: return null
            // Buscar arrays llamados "chapters" o "chapterList"
            val candidates = listOf("chapters","chapterList","volumes","episodes")
            for (c in candidates) {
                val arr = pageProps.optJSONArray(c) ?: continue
                val out = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                if (out.isNotEmpty()) return out
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractImageListFromNextJson(json: JSONObject?): List<String>? {
        if (json == null) return null
        try {
            val props = json.optJSONObject("props") ?: return null
            val pageProps = props.optJSONObject("pageProps") ?: return null

            // posibles claves: images, pages, imageList
            val candidates = listOf("images", "pages", "imageList", "imagesArr")
            for (c in candidates) {
                val arr = pageProps.optJSONArray(c) ?: continue
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) out.add(v)
                }
                if (out.isNotEmpty()) return out
            }

            // buscar en sharedData
            val shared = pageProps.optJSONObject("sharedData")
            shared?.let {
                shared.keys().forEach { k ->
                    val o = shared.optJSONObject(k)
                    o?.optJSONArray("images")?.let { arr ->
                        val out = mutableListOf<String>()
                        for (i in 0 until arr.length()) out.add(arr.optString(i))
                        if (out.isNotEmpty()) return out
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractSlugFromDocLocation(location: String?): String {
        if (location == null) return ""
        val parts = location.split("/").filter { it.isNotBlank() }
        // buscar "title" y tomar el siguiente segmento
        val idx = parts.indexOf("title")
        if (idx >= 0 && idx + 1 < parts.size) return parts[idx + 1]
        // fallback: último segmento
        return parts.lastOrNull() ?: ""
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Android) Mihon/1.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )
}
