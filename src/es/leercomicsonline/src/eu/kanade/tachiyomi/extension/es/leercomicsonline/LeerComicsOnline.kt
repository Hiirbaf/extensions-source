package eu.kanade.tachiyomi.extension.es.leercomicsonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class LeerComicsOnline : HttpSource() {

    override val name = "Leer Comics Online"
    override val baseUrl = "https://leercomicsonline.com"
    override val lang = "es"
    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val comicsPerPage = 20

    override fun popularMangaRequest(page: Int): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addPathSegments("series")
            addQueryParameter("page", page.toString())
        }.build(),
        headers,
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(
            baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("api")
                addPathSegments("series")
                addQueryParameter("page", page.toString())
                addQueryParameter("search", query)
            }.build(),
            headers,
        )

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")!!.toInt()
        val comics = json.decodeFromString<List<Comic>>(response.body.string())
        return MangasPage(
            comics.subList((page - 1) * comicsPerPage, page * comicsPerPage).map { comic ->
                SManga.create().apply {
                    initialized = true
                    title = comic.title
                    thumbnail_url = "$baseUrl/images/${comic.url}-300x461.jpg"
                    setUrlWithoutDomain(
                        "/api/comics?serieId=${comic.id}&slug=${comic.url}"
                    )
                }
            },
            comics.count() > (page * comicsPerPage),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val mangasPage = popularMangaParse(response)
        val searchQuery = response.request.url.queryParameter("search").toString()
        return MangasPage(
            mangasPage.mangas.filter {
                it.title.lowercase().contains(searchQuery, ignoreCase = true)
            },
            mangasPage.hasNextPage,
        )
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/categorias/${manga.url.toHttpUrl().queryParameter("slug")}"

    override fun chapterListRequest(manga: SManga): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addEncodedPathSegments(manga.url.removePrefix("/"))
        }.build(),
        headers
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.queryParameter("slug")!!
        val comics = json.decodeFromString<List<Comic>>(response.body.string())
        return comics.reversed().map {
            SChapter.create().apply {
                name = it.title
                setUrlWithoutDomain("/api/pages?id=${it.id}&letter=${slug.first()}&slug=$slug")
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.url.toHttpUrl().queryParameter("slug") ?: return baseUrl
        return "$baseUrl/$slug"
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(
        baseUrl.toHttpUrl().newBuilder().apply {
            addEncodedPathSegments("api/pages")
            addQueryParameter("id", chapter.url.toHttpUrl().queryParameter("id"))
            addQueryParameter("letter", chapter.url.toHttpUrl().queryParameter("letter"))
        }.build(),
        headers
    )

    override fun pageListParse(response: Response): List<Page> {
        return try {
            json.decodeFromString<List<String>>(response.body.string()).mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException()

    @Serializable
    class Comic(
        val title: String,
        val url: String,
        val id: Int,
    )
}
