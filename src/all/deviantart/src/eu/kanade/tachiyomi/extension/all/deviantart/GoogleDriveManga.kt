package eu.kanade.tachiyomi.extension.all.googledrivemanga

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import org.jsoup.nodes.Document

class GoogleDriveManga : HttpSource() {

override val name = "Google Drive Manga"

override val baseUrl = "https://drive.google.com"

override val lang = "all"

override val supportsLatest = false

override fun popularMangaRequest(page: Int): Request {
    throw UnsupportedOperationException()
}

override fun popularMangaParse(response: okhttp3.Response): MangasPage {
    throw UnsupportedOperationException()
}

override fun latestUpdatesRequest(page: Int): Request {
    throw UnsupportedOperationException()
}

override fun latestUpdatesParse(response: okhttp3.Response): MangasPage {
    throw UnsupportedOperationException()
}

override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
    throw UnsupportedOperationException()
}

override fun searchMangaParse(response: okhttp3.Response): MangasPage {
    throw UnsupportedOperationException()
}

override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headers)

override fun mangaDetailsParse(response: okhttp3.Response): SManga {
    val manga = SManga.create()
    manga.title = "Google Drive Folder"
    manga.author = "Unknown"
    manga.artist = "Unknown"
    manga.status = SManga.ONGOING
    manga.genre = "Drive Folder"
    manga.description = "Folder from Google Drive containing manga."
    manga.thumbnail_url = null
    return manga
}

override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headers)

override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
    val document = response.asJsoup()
    val chapters = mutableListOf<SChapter>()

    document.select("a[href]").forEach { link ->
        val href = link.attr("href")
        if (href.contains("/folders/")) {
            val id = href.substringAfter("/folders/").substringBefore("?")
            val chapter = SChapter.create()
            chapter.url = "$baseUrl/drive/folders/$id"
            chapter.name = link.text()
            chapter.date_upload = 0L
            chapters.add(chapter)
        }
    }

    return chapters
}

override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

override fun pageListParse(response: okhttp3.Response): List<Page> {
    val document = response.asJsoup()
    val pages = mutableListOf<Page>()

    document.select("a[href]").forEachIndexed { index, link ->
        val href = link.attr("href")
        if (href.contains("/file/d/")) {
            val id = href.substringAfter("/file/d/").substringBefore("/")
            val imageUrl = "$baseUrl/uc?id=$id&export=download"
            pages.add(Page(index, "", imageUrl))
        }
    }

    return pages
}

override fun imageUrlParse(response: okhttp3.Response): String {
    return response.request.url.toString()
}

private fun okhttp3.Response.asJsoup(): Document {
    return org.jsoup.Jsoup.parse(this.body?.string())
}

}

