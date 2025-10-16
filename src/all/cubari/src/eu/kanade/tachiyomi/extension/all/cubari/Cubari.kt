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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/", headers)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response -> latestUpdatesParse(response) }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        return parseMangaList(result, SortType.UNPINNED)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/", headers)
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response -> popularMangaParse(response) }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        return parseMangaList(result, SortType.PINNED)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { response -> mangaDetailsParse(response, manga) }
    }

    // Called when the series is loaded, or when opening in browser
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        throw UnsupportedOperationException()
    }

    private fun mangaDetailsParse(response: Response, manga: SManga): SManga {
        val result = json.parseToJsonElement(response.body.string()).jsonObject
        return parseManga(result, manga)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservable()
            .map { response -> chapterListParse(response, manga) }
    }

    // Gets the chapter list based on the series being viewed
    override fun chapterListRequest(manga: SManga): Request {
        val urlComponents = manga.url.split("/").filter { it.isNotEmpty() }
        if (urlComponents.size < 3) {
            return GET("$baseUrl${manga.url}", headers)
        }
        val source = urlComponents[1]
        val slug = urlComponents[2]
        return GET("$baseUrl/read/api/$source/series/$slug/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        throw Exception("Unused")
    }

    // Called after the request
    private fun chapterListParse(response: Response, manga: SManga): List<SChapter> {
        val res = response.body.string()
        return parseChapterList(res, manga)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return when {
            chapter.url.contains("/chapter/") -> {
                client.newCall(pageListRequest(chapter))
                    .asObservableSuccess()
                    .map { response ->
                        directPageListParse(response)
                    }
            }
            else -> {
                client.newCall(pageListRequest(chapter))
                    .asObservableSuccess()
                    .map { response ->
                        seriesJsonPageListParse(response, chapter)
                    }
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return when {
            chapter.url.contains("/chapter/") -> {
                GET("$baseUrl${chapter.url}", headers)
            }
            else -> {
                val url = chapter.url.split("/").filter { it.isNotEmpty() }
                if (url.size < 3) return GET("$baseUrl${chapter.url}", headers)
                val source = url[1]
                val slug = url[2]
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
        val chapterScanlator = chapter.scanlator ?: "default" // workaround for "" as group causing NullPointerException (#13772)

        // prevent NullPointerException when chapters.key is 084 and chapter.chapter_number is 84
        val chapters = jsonObj["chapters"]!!.jsonObject.let { chaptersObj ->
            chaptersObj.entries.associateBy(
                { it.key.replace(Regex("^0+(?!$)"), "") },
                { it.value },
            )
        }

        val chapterKey = chapter.chapter_number.toString()
        val chapterData = chapters[chapterKey] ?: chapters[chapter.chapter_number.toInt().toString()]

        val pages = chapterData
            ?.jsonObject
            ?.get("groups")
            ?.jsonObject
            ?.get(groupMap[chapterScanlator])
            ?.jsonArray
            ?: return emptyList()

        return pages.mapIndexed { i, jsonEl ->
            val page = if (jsonEl is JsonObject) {
                jsonEl.jsonObject["src"]!!.jsonPrimitive.content
            } else {
                jsonEl.jsonPrimitive.content
            }

            Page(i, "", page)
        }
    }

    // Stub
    override fun pageListParse(response: Response): List<Page> {
        throw Exception("Unused")
    }

    private val homeClient by lazy {
        client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
    }

    private val tagClient by lazy {
        client.newBuilder()
            .addInterceptor(RemoteStorageUtils.TagInterceptor())
            .build()
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return Observable.just(MangasPage(emptyList(), false))
        }

        if (trimmedQuery.startsWith(PROXY_PREFIX)) {
            val slugs = trimmedQuery.removePrefix(PROXY_PREFIX)
                .split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return Observable.fromIterable(slugs)
                .flatMap { slug ->
                    tagClient.newCall(proxySearchRequest(slug))
                        .asObservableSuccess()
                        .map { response -> proxySearchParse(response, slug).mangas }
                        .onErrorReturn { emptyList() }
                }
                .toList()
                .map { lists ->
                    val allMangas = lists.flatten()
                    MangasPage(allMangas, false)
                }
                .toObservable()
        }

        // Búsqueda normal
        return homeClient.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                val mangasPage = searchMangaParse(response, query)
                if (mangasPage.mangas.isEmpty()) throw Exception(SEARCH_FALLBACK_MSG)
                mangasPage
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/", headers)
    }

    private fun proxySearchRequest(query: String): Request {
        val queryFragments = query.split("/")
        if (queryFragments.size < 2 || queryFragments[0].isBlank() || queryFragments[1].isBlank()) {
            throw Exception(SEARCH_FALLBACK_MSG)
        }
        val source = queryFragments[0]
        val slug = queryFragments[1]
        return GET("$baseUrl/read/api/$source/series/$slug/", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        throw UnsupportedOperationException()
    }

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonArray
        val normalizedQuery = query.trim().lowercase()

        val filterList = result.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { jsonObj ->
                val title = jsonObj["title"]?.jsonPrimitive?.content?.lowercase() ?: ""
                title.contains(normalizedQuery)
            }
            .take(50)
            .toList()

        return parseMangaList(JsonArray(filterList), SortType.ALL)
    }

    private fun proxySearchParse(response: Response, query: String): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonObject
        return parseSearchList(result, query)
    }

    // ------------- Helpers and whatnot ---------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    private fun parseChapterList(payload: String, manga: SManga): List<SChapter> {
        val jsonObj = json.parseToJsonElement(payload).jsonObject
        val groups = jsonObj["groups"]!!.jsonObject
        val chapters = jsonObj["chapters"]!!.jsonObject
        val seriesSlug = jsonObj["slug"]!!.jsonPrimitive.content

        val seriesPrefs = Injekt.get<Application>().getSharedPreferences("source_${id}_updateTime:$seriesSlug", 0)
        val currentTimeMillis = System.currentTimeMillis()

        val existingTimestamps = mutableMapOf<String, Long>()
        val seriesPrefsEditor = seriesPrefs.edit()
        var hasNewChapters = false

        val chapterList = chapters.entries.asSequence().flatMap { chapterEntry ->
            val chapterNum = chapterEntry.key
            val chapterObj = chapterEntry.value.jsonObject
            val chapterGroups = chapterObj["groups"]!!.jsonObject
            val volume = chapterObj["volume"]!!.jsonPrimitive.content.let {
                if (volumeNotSpecifiedTerms.contains(it)) null else it
            }
            val title = chapterObj["title"]!!.jsonPrimitive.content

            chapterGroups.entries.asSequence().map { groupEntry ->
                val groupNum = groupEntry.key
                val releaseDate = chapterObj["release_date"]?.jsonObject?.get(groupNum)

                SChapter.create().apply {
                    scanlator = groups[groupNum]!!.jsonPrimitive.content
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f

                    date_upload = if (releaseDate != null) {
                        releaseDate.jsonPrimitive.double.toLong() * 1000
                    } else {
                        if (!seriesPrefs.contains(chapterNum)) {
                            seriesPrefsEditor.putLong(chapterNum, currentTimeMillis)
                            hasNewChapters = true
                            currentTimeMillis
                        } else {
                            seriesPrefs.getLong(chapterNum, currentTimeMillis)
                        }
                    }

                    name = buildString {
                        if (!volume.isNullOrBlank()) append("Vol.$volume ")
                        append("Ch.$chapterNum")
                        if (title.isNotBlank()) append(" - $title")
                    }

                    url = if (chapterGroups[groupNum] is JsonArray) {
                        "${manga.url}/$chapterNum/$groupNum"
                    } else {
                        chapterGroups[groupNum]!!.jsonPrimitive.content
                    }
                }
            }
        }.toList()

        if (hasNewChapters) {
            seriesPrefsEditor.apply()
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull { jsonEl ->
            val jsonObj = jsonEl.jsonObject
            val pinned = jsonObj["pinned"]!!.jsonPrimitive.boolean

            when {
                sortType == SortType.PINNED && pinned -> parseManga(jsonObj)
                sortType == SortType.UNPINNED && !pinned -> parseManga(jsonObj)
                sortType == SortType.ALL -> parseManga(jsonObj)
                else -> null
            }
        }

        return MangasPage(mangaList, false)
    }

    private fun parseSearchList(payload: JsonObject, query: String): MangasPage {
        val tempManga = SManga.create().apply {
            url = "/read/$query"
        }

        val mangaList = listOf(parseManga(payload, tempManga))

        return MangasPage(mangaList, false)
    }

    private fun parseManga(jsonObj: JsonObject, mangaReference: SManga? = null): SManga =
        SManga.create().apply {
            title = jsonObj["title"]!!.jsonPrimitive.content
            artist = jsonObj["artist"]?.jsonPrimitive?.content ?: ARTIST_FALLBACK
            author = jsonObj["author"]?.jsonPrimitive?.content ?: AUTHOR_FALLBACK

            val descriptionFull = jsonObj["description"]?.jsonPrimitive?.content
            description = descriptionFull?.substringBefore("Tags: ") ?: DESCRIPTION_FALLBACK
            genre = descriptionFull?.let {
                if (it.contains("Tags: ")) {
                    it.substringAfter("Tags: ")
                } else {
                    ""
                }
            } ?: ""

            url = mangaReference?.url ?: jsonObj["url"]!!.jsonPrimitive.content
            thumbnail_url = jsonObj["coverUrl"]?.jsonPrimitive?.content
                ?: jsonObj["cover"]?.jsonPrimitive?.content ?: ""
        }

    // ----------------- Things we aren't supporting -----------------

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PROXY_PREFIX = "cubari:"
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Unable to parse. Is your query in the format of $PROXY_PREFIX<source>/<slug>?"

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}
