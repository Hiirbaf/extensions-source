package eu.kanade.tachiyomi.extension.all.googledrive

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GoogleDriveSource : ParsedHttpSource() {

    override val name = "Google Drive (Público)"
    override val baseUrl = "https://drive.google.com"
    override val lang = "all"
    override val supportsLatest = false

    // TODO: Cambia esto por tu carpeta raíz pública
    private val rootFolderId = "1A2B3C4D5E6F"

    override fun latestUpdatesRequest(page: Int): Request {
        return Request.Builder().url(baseUrl).build()
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/drive/folders/$rootFolderId"
        return GET(url, headers)
    }

    override fun popularMangaSelector(): String = "div[role='listitem']"

    override fun popularMangaFromElement(element: Element): SManga {
        val title = element.select("div[aria-label]").attr("aria-label")
        val folderId = element.select("a").attr("href")
            .substringAfter("/folders/").substringBefore("?")

        return SManga.create().apply {
            this.title = title
            this.url = "/drive/folders/$folderId"
            this.thumbnail_url = null
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            author = "Desconocido"
            artist = "Desconocido"
            genre = "Google Drive"
            description = "Contenido cargado desde una carpeta pública de Google Drive"
            status = SManga.ONGOING
        }
    }

    override fun chapterListSelector(): String = "div[role='listitem']"

    override fun chapterFromElement(element: Element): SChapter {
        val name = element.select("div[aria-label]").attr("aria-label")
        val chapterId = element.select("a").attr("href")
            .substringAfter("/folders/").substringBefore("?")

        return SChapter.create().apply {
            this.name = name
            this.url = "/drive/folders/$chapterId"
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val elements = document.select("div[role='listitem']")
        return elements.mapIndexedNotNull { index, el ->
            val name = el.select("div[aria-label]").attr("aria-label")
            if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp")) {
                val fileId = el.select("a").attr("href")
                    .substringAfter("/file/d/").substringBefore("/")
                Page(index, "", "https://drive.google.com/uc?export=download&id=$fileId")
            } else {
                null
            }
        }
    }

    override fun imageUrlParse(document: Document): String = ""

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    override fun searchMangaNextPageSelector(): String? = null
}
