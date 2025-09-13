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
        val groupMap = groups.entries.associateBy(
            { it.value.jsonPrimitive.content.ifEmpty { "default" } },
            { it.key },
        )
        val chapterScanlator = chapter.scanlator ?: "default"

        val chapters = jsonObj["chapters"]!!.jsonObject.let { chaptersObj ->
            // Crear mapa normalizado una sola vez
            chaptersObj.entries.associateBy(
                { it.key.replace(Regex("^0+(?!$)"), "") },
                { it.value },
            )
        }

        val chapterKey = chapter.chapter_number.toString()
        val chapterData = chapters[chapterKey] ?: chapters[chapter.chapter_number.toInt().toString()]

        val pages = chapterData
            ?.jsonObject?.get("groups")
            ?.jsonObject?.get(groupMap[chapterScanlator])
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

    override fun pageListParse(response: Response): List<Page> {
        throw Exception("Unused")
    }

    // ------------------- Search -------------------

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PROXY_PREFIX) -> {
                val trimmedQuery = query.removePrefix(PROXY_PREFIX)
                if (trimmedQuery.isBlank()) {
                    Observable.just(MangasPage(emptyList(), false))
                } else {
                    client.newBuilder()
                        .addInterceptor(RemoteStorageUtils.TagInterceptor())
                        .build()
                        .newCall(proxySearchRequest(trimmedQuery))
                        .asObservableSuccess()
                        .map { proxySearchParse(it, trimmedQuery) }
                }
            }
            query.isBlank() -> {
                Observable.just(MangasPage(emptyList(), false))
            }
            else -> {
                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.HomeInterceptor())
                    .build()
                    .newCall(searchMangaRequest(page, query, filters))
                    .asObservableSuccess()
                    .map { searchMangaParse(it, query) }
                    .map { mangasPage ->
                        if (mangasPage.mangas.isEmpty()) {
                            throw Exception(SEARCH_FALLBACK_MSG)
                        }
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
        val normalizedQuery = query.trim().lowercase()

        val filterList = result.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { jsonObj ->
                val title = jsonObj["title"]?.jsonPrimitive?.content?.lowercase() ?: ""
                title.contains(normalizedQuery)
            }
            .take(50) // Limitar resultados para mejor rendimiento
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

    private fun parseChapterList(payload: String, manga: SManga): List<SChapter> {
        val jsonObj = json.parseToJsonElement(payload).jsonObject
        val groups = jsonObj["groups"]!!.jsonObject
        val chapters = jsonObj["chapters"]!!.jsonObject
        val seriesSlug = jsonObj["slug"]!!.jsonPrimitive.content

        val seriesPrefs = Injekt.get<Application>().getSharedPreferences("source_${id}_updateTime:$seriesSlug", 0)
        val currentTimeMillis = System.currentTimeMillis()

        // Obtener todos los valores de una vez para evitar múltiples accesos al disco
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

        // Solo aplicar cambios si hay capítulos nuevos
        if (hasNewChapters) {
            seriesPrefsEditor.apply()
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull { jsonEl ->
            val jsonObj = jsonEl.jsonObject
            val pinned = jsonObj["pinned"]!!.jsonPrimitive.boolean

            if (sortType == SortType.PINNED && pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.UNPINNED && !pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.ALL) {
                parseManga(jsonObj)
            } else {
                null
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
