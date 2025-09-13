package eu.kanade.tachiyomi.extension.all.cubari

import android.app.Application
import android.os.Build
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

open class Cubari(override val lang: String) : HttpSource() {

    final override val name = "Cubari"
    final override val baseUrl = "https://cubari.moe"
    final override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add(
            "User-Agent",
            "(Android ${Build.VERSION.RELEASE}; " +
                "${Build.MANUFACTURER} ${Build.MODEL}) " +
                "Tachiyomi/${AppInfo.getVersionName()} " +
                Build.ID,
        )
    }

    // ------------------- Latest & Popular -------------------

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { latestUpdatesParse(it) }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        return parseMangaList(result, SortType.UNPINNED)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { popularMangaParse(it) }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        return parseMangaList(result, SortType.PINNED)
    }

    // ------------------- Manga Details -------------------

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { mangaDetailsParse(it, manga) }
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        throw UnsupportedOperationException()
    }

    private fun mangaDetailsParse(response: Response, manga: SManga): SManga {
        val result = json.parseToJsonElement(response.body.string()).jsonObject
        return parseManga(result, manga)
    }

    // ------------------- Chapter List -------------------

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservable()
            .map { chapterListParse(it, manga) }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val urlComponents = manga.url.split("/")
        val source = urlComponents[2]
        val slug = urlComponents[3]
        return GET("$baseUrl/read/api/$source/series/$slug/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        throw Exception("Unused")
    }

    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val res = response.body.string()
        return parseChapterList(res, manga)
    }

    // ------------------- Pages -------------------

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return when {
            chapter.url.contains("/chapter/") ->
                client.newCall(pageListRequest(chapter))
                    .asObservableSuccess()
                    .map { directPageListParse(it) }
            else ->
                client.newCall(pageListRequest(chapter))
                    .asObservableSuccess()
                    .map { seriesJsonPageListParse(it, chapter) }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return when {
            chapter.url.contains("/chapter/") ->
                GET("$baseUrl${chapter.url}", headers)
            else -> {
                val url = chapter.url.split("/")
                val source = url[2]
                val slug = url[3]
                GET("$baseUrl/read/api/$source/series/$slug/", headers)
            }
        }
    }

    private fun directPageListParse(response: Response): List<Page> {
        val res = response.body.string()
        val pages = json.parseToJsonElement(res).jsonArray
        return pages.mapIndexed { i, jsonEl ->
            val page = if (jsonEl is JsonObject) {
                jsonEl.jsonObject["src"]!!.jsonPrimitive.content
            } else {
                jsonEl.jsonPrimitive.content
            }
            Page(i, "", page)
        }
    }

    private fun seriesJsonPageListParse(response: Response, chapter: SChapter): List<Page> {
        val jsonObj = json.parseToJsonElement(response.body.string()).jsonObject
        val groups = jsonObj["groups"]!!.jsonObject
        val groupMap = groups.entries.associateBy({ it.value.jsonPrimitive.content.ifEmpty { "default" } }, { it.key })
        val chapterScanlator = chapter.scanlator ?: "default"

        val chapters = jsonObj["chapters"]!!.jsonObject.mapKeys {
            it.key.replace(Regex("^0+(?!$)"), "")
        }

        val pages = if (chapters[chapter.chapter_number.toString()] != null) {
            chapters[chapter.chapter_number.toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        } else {
            chapters[chapter.chapter_number.toInt().toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        }

        return pages.mapIndexed { i, jsonEl ->
            val page = if (jsonEl is JsonObject) {
                jsonEl.jsonObject["src"]!!.jsonPrimitive.content
            } else {
                jsonEl.jsonPrimitive.content
            }
            Page(i, "", page)
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        throw Exception("Unused")
    }

    // ------------------- Search -------------------

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PROXY_PREFIX) -> {
                val trimmedQuery = query.removePrefix(PROXY_PREFIX)
                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.TagInterceptor())
                    .build()
                    .newCall(proxySearchRequest(trimmedQuery))
                    .asObservableSuccess()
                    .map { proxySearchParse(it, trimmedQuery) }
            }
            else -> {
                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.HomeInterceptor())
                    .build()
                    .newCall(searchMangaRequest(page, query, filters))
                    .asObservableSuccess()
                    .map { searchMangaParse(it, query) }
                    .map { mangasPage ->
                        require(mangasPage.mangas.isNotEmpty()) { SEARCH_FALLBACK_MSG }
                        mangasPage
                    }
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/", headers)

    private fun proxySearchRequest(query: String): Request {
        val queryFragments = query.split("/")
        val source = queryFragments[0]
        val slug = queryFragments[1]
        return GET("$baseUrl/read/api/$source/series/$slug/", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        throw UnsupportedOperationException()
    }

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        val filterList = result.asSequence()
            .map { it as JsonObject }
            .filter { it["title"].toString().contains(query.trim(), true) }
            .toList()
        return parseMangaList(JsonArray(filterList), SortType.ALL)
    }

    private fun proxySearchParse(response: Response, query: String): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonObject
        return parseSearchList(result, query)
    }

    // ------------------- Helpers -------------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    // (Los métodos parseMangaList, parseSearchList, parseManga, parseChapterList los dejas igual)

    // ------------------- Stubs -------------------

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PROXY_PREFIX = "cubari:"
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG =
            "Unable to parse. Is your query in the format of $PROXY_PREFIX<source>/<slug>?"

        enum class SortType {
            PINNED, UNPINNED, ALL
        }
    }
}
