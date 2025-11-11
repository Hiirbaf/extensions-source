package eu.kanade.tachiyomi.extension.en.aurora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
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

    // ✅ Necesario: override headers correctamente
    override val headers: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Mihon/1.0")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    // --- Popular (browse) ---
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/browser?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val html = response.body!!.string()
        val doc = Jsoup.parse(html)
        val mangas = mutableListOf<SManga>()

        val json = extractNextDataJson(doc)
        if (json != null) {
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

        val json = extractNextDataJson(doc)
        val imgsFromJson = extractImageListFromNextJson(json)
        if (imgsFromJson != null && imgsFromJson.isNotEmpty()) {
            imgsFromJson.forEachIndexed { i, url -> pages.add(Page(i, "", url)) }
            return pages
        }

        val imgEls = doc.select(".reader img, .page img, .viewer img, .comic-page img")
        imgEls.forEachIndexed { i, el ->
            val src = el.attr("data-src").ifEmpty { el.attr("src") }
            if (src.isNotBlank()) pages.add(Page(i, "", src))
        }

        if (pages.isEmpty()) {
            val scriptWithImgs = doc.select("script").firstOrNull { it.data().contains("images") || it.data().contains("pages") }
            scriptWithImgs?.let {
                val text = it.data()
                val regex = """https?://[^\s'"]+\.(?:jpg|jpeg|png|webp)""".toRegex()
                val matches = regex.findAll(text).map { m -> m.value }.toList()
                matches.forEachIndexed { i, u -> pages.add(Page(i, "", u)) }
            }
        }

        return pages
    }

    // ✅ Requerido por HttpSource
    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // --- Helpers (sin cambios) ---
    private fun extractNextDataJson(doc: Document): JSONObject? { /* ... */ return null }
    private fun extractListFromNextJson(json: JSONObject): List<JSONObject>? { /* ... */ return null }
    private fun extractMangaObjectFromNextJson(json: JSONObject, location: String): JSONObject? { /* ... */ return null }
    private fun extractChaptersFromNextJson(json: JSONObject): List<JSONObject>? { /* ... */ return null }
    private fun extractImageListFromNextJson(json: JSONObject?): List<String>? { /* ... */ return null }
    private fun extractSlugFromDocLocation(location: String?): String { /* ... */ return "" }
}
