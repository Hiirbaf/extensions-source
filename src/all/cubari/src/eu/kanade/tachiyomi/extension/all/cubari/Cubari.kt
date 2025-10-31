package eu.kanade.tachiyomi.extension.all.cubari

import android.os.Build
import android.util.Base64
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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class Cubari(override val lang: String) : HttpSource() {

    override val name = "Cubari"

    override val baseUrl = "https://cubari.moe"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .build()

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

    private val cubariHeaders = super.headersBuilder()
        .set(
            "User-Agent",
            "(Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}) " +
                "Tachiyomi/${AppInfo.getVersionName()} ${Build.ID} Keiyoushi",
        )
        .build()

    // --------------------- Popular & Latest ---------------------

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/", cubariHeaders)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/", cubariHeaders)

    override fun fetchPopularManga(page: Int) =
        fetchMangaList(popularMangaRequest(page), ::popularMangaParse)

    override fun fetchLatestUpdates(page: Int) =
        fetchMangaList(latestUpdatesRequest(page), ::latestUpdatesParse)

    private fun fetchMangaList(request: Request, parser: (Response) -> MangasPage): Observable<MangasPage> =
        homeClient.newCall(request)
            .asObservableSuccess()
            .map(parser)

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaList(response.parseAs(), SortType.PINNED)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaList(response.parseAs(), SortType.UNPINNED)

    // --------------------- Manga Details ---------------------

    override fun mangaDetailsRequest(manga: SManga) = chapterListRequest(manga)
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { mangaDetailsParse(it, manga) }

    private fun mangaDetailsParse(response: Response, manga: SManga): SManga =
        parseManga(response.parseAs(), manga)

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    // --------------------- Chapters ---------------------

    override fun chapterListRequest(manga: SManga): Request {
        val (source, slug) = manga.url.split("/").let { it[2] to it[3] }
        return GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        client.newCall(chapterListRequest(manga))
            .asObservable()
            .map { parseChapterList(it, manga) }

    // --------------------- Pages ---------------------

    private fun isDirectChapter(chapter: SChapter) = "/chapter/" in chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        return if (isDirectChapter(chapter)) {
            GET("$baseUrl${chapter.url}", cubariHeaders)
        } else {
            val (source, slug) = chapter.url.split("/").let { it[2] to it[3] }
            GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                if (isDirectChapter(chapter)) {
                    directPageListParse(response)
                } else {
                    seriesJsonPageListParse(response, chapter)
                }
            }

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException()

    private fun directPageListParse(response: Response): List<Page> =
        response.parseAs<JsonArray>().mapIndexed { i, json ->
            val src = when (json) {
                is JsonObject -> json["src"]!!.jsonPrimitive.content
                else -> json.jsonPrimitive.content
            }
            Page(i, "", src)
        }

    private fun seriesJsonPageListParse(response: Response, chapter: SChapter): List<Page> {
        val obj = response.parseAs<JsonObject>()
        val groups = obj["groups"]!!.jsonObject
        val groupMap = groups.entries.associate { 
            it.value.jsonPrimitive.content.ifEmpty { "default" } to it.key 
        }
        val chapters = obj["chapters"]!!.jsonObject.mapKeys { 
            it.key.replace(Regex("^0+(?!$)"), "") 
        }

        val chapterNum = chapter.chapter_number.toString()
        val scanlator = chapter.scanlator ?: "default"

        val pages = (chapters[chapterNum] ?: chapters[chapter.chapter_number.toInt().toString()])
            ?.jsonObject?.get("groups")
            ?.jsonObject?.get(groupMap[scanlator])
            ?.jsonArray
            ?: throw Exception("Chapter pages not found")

        return pages.mapIndexed { i, json ->
            val src = when (json) {
                is JsonObject -> json["src"]!!.jsonPrimitive.content
                else -> json.jsonPrimitive.content
            }
            Page(i, "", src)
        }
    }

    // --------------------- Search ---------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/", cubariHeaders)

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith("https://") || query.startsWith("cubari:") -> {
                val (source, slug) = deepLinkHandler(query)
                tagClient.newCall(GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders))
                    .asObservableSuccess()
                    .map { res ->
                        val obj = res.parseAs<JsonObject>()
                        val manga = SManga.create().apply { url = "/read/$source/$slug" }
                        MangasPage(listOf(parseManga(obj, manga)), false)
                    }
            }
            else -> {
                homeClient.newCall(searchMangaRequest(page, query, filters))
                    .asObservableSuccess()
                    .map { searchMangaParse(it, query) }
                    .map {
                        require(it.mangas.isNotEmpty()) { SEARCH_FALLBACK_MSG }
                        it
                    }
            }
        }
    }

    private fun deepLinkHandler(query: String): Pair<String, String> {
        if (query.startsWith("cubari:")) {
            val parts = query.removePrefix("cubari:").split("/", limit = 2)
            require(parts.size == 2) { SEARCH_FALLBACK_MSG }
            return parts[0] to parts[1]
        }

        val url = query.toHttpUrl()
        val host = url.host
        val path = url.pathSegments

        return when {
            host.endsWith("imgur.com") && path.size >= 2 && path[0] in listOf("a", "gallery") ->
                "imgur" to path[1]
            host.endsWith("reddit.com") && path.size >= 2 && path[0] == "gallery" ->
                "reddit" to path[1]
            host == "imgchest.com" && path.size >= 2 && path[0] == "p" ->
                "imgchest" to path[1]
            host.endsWith("catbox.moe") && path.size >= 2 && path[0] == "c" ->
                "catbox" to path[1]
            host.endsWith("cubari.moe") && path.size >= 3 ->
                path[1] to path[2]
            host.endsWith(".githubusercontent.com") -> {
                val src = host.substringBefore(".")
                val encoded = Base64.encodeToString(
                    "$src${url.encodedPath}".toByteArray(), 
                    Base64.NO_PADDING
                )
                "gist" to encoded
            }
            else -> throw Exception(SEARCH_FALLBACK_MSG)
        }
    }

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val filtered = response.parseAs<JsonArray>()
            .map { it as JsonObject }
            .filter { it["title"].toString().contains(query.trim(), ignoreCase = true) }
        return parseMangaList(JsonArray(filtered), SortType.ALL)
    }

    // ------------- Helpers and whatnot ---------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    private fun parseChapterList(response: Response, manga: SManga): List<SChapter> {
        val jsonObj = response.parseAs<JsonObject>()
        val groups = jsonObj["groups"]!!.jsonObject
        val chapters = jsonObj["chapters"]!!.jsonObject

        return chapters.entries.flatMap { (num, chapterEl) ->
            val chapterObj = chapterEl.jsonObject
            val chapterGroups = chapterObj["groups"]!!.jsonObject
            val volume = chapterObj["volume"]!!.jsonPrimitive.content.takeUnless { it in volumeNotSpecifiedTerms }
            val title = chapterObj["title"]!!.jsonPrimitive.content

            chapterGroups.entries.map { (groupNum, groupValue) ->
                val releaseDate = chapterObj["release_date"]?.jsonObject?.get(groupNum)
                SChapter.create().apply {
                    scanlator = groups[groupNum]!!.jsonPrimitive.content
                    chapter_number = num.toFloatOrNull() ?: -1f
                    date_upload = releaseDate?.jsonPrimitive?.double?.toLong()?.times(1000) ?: 0L
                    name = buildString {
                        if (!volume.isNullOrBlank()) append("Vol.$volume ")
                        append("Ch.$num")
                        title.takeIf { it.isNotBlank() }?.let { append(" - $it") }
                    }
                    url = if (groupValue is JsonArray) "${manga.url}/$num/$groupNum"
                    else groupValue.jsonPrimitive.content
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull {
            val jsonObj = it.jsonObject
            val pinned = jsonObj["pinned"]!!.jsonPrimitive.boolean
            when (sortType) {
                SortType.ALL -> parseManga(jsonObj)
                SortType.PINNED -> if (pinned) parseManga(jsonObj) else null
                SortType.UNPINNED -> if (!pinned) parseManga(jsonObj) else null
            }
        }

        return MangasPage(mangaList, false)
    }

    private fun parseManga(jsonObj: JsonObject, mangaReference: SManga? = null): SManga =
        SManga.create().apply {
            title = jsonObj["title"]!!.jsonPrimitive.content
            artist = jsonObj["artist"]?.jsonPrimitive?.content ?: ARTIST_FALLBACK
            author = jsonObj["author"]?.jsonPrimitive?.content ?: AUTHOR_FALLBACK

            val descriptionFull = jsonObj["description"]?.jsonPrimitive?.content
            description = descriptionFull?.substringBefore("Tags: ") ?: DESCRIPTION_FALLBACK
            genre = when {
                descriptionFull == null -> ""
                "Tags: " in descriptionFull -> descriptionFull.substringAfter("Tags: ")
                else -> ""
            }

            url = mangaReference?.url ?: jsonObj["url"]!!.jsonPrimitive.content
            thumbnail_url = jsonObj["coverUrl"]?.jsonPrimitive?.content
                ?: jsonObj["cover"]?.jsonPrimitive?.content
                ?: ""
        }

    // ----------------- Things we aren't supporting -----------------

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    companion object {
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Please enter a valid Cubari URL"

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}
