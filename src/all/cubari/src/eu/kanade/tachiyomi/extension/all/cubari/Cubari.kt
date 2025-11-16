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
            chain.proceed(
                request.newBuilder().headers(headers).build()
            )
        }
        .build()

    private val cubariHeaders = super.headersBuilder()
        .set(
            "User-Agent",
            "(Android ${Build.VERSION.RELEASE}; " +
                "${Build.MANUFACTURER} ${Build.MODEL}) Tachiyomi/${AppInfo.getVersionName()} ${Build.ID} Hybrid"
        )
        .build()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/", cubariHeaders)

    override fun fetchLatestUpdates(page: Int) =
        client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { latestUpdatesParse(it) }

    override fun latestUpdatesParse(response: Response) =
        parseMangaList(response.parseAs(), SortType.UNPINNED)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/", cubariHeaders)

    override fun fetchPopularManga(page: Int) =
        client.newBuilder()
            .addInterceptor(RemoteStorageUtils.HomeInterceptor())
            .build()
            .newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { popularMangaParse(it) }

    override fun popularMangaParse(response: Response) =
        parseMangaList(response.parseAs(), SortType.PINNED)

    // ========= MANGA / CHAPTERS =========

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun fetchMangaDetails(manga: SManga) =
        client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { mangaDetailsParse(it, manga) }

    override fun mangaDetailsRequest(manga: SManga): Request =
        chapterListRequest(manga)

    override fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException()

    private fun mangaDetailsParse(response: Response, manga: SManga): SManga =
        parseManga(response.parseAs(), manga)

    override fun fetchChapterList(manga: SManga) =
        client.newCall(chapterListRequest(manga))
            .asObservable()
            .map { chapterListParse(it, manga) }

    override fun chapterListRequest(manga: SManga): Request {
        val s = manga.url.split("/")
        val source = s[2]
        val slug = s[3]
        return GET("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException()

    private fun chapterListParse(response: Response, manga: SManga) =
        parseChapterList(response, manga)

    // ========= PAGES =========

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map {
                if (chapter.url.contains("/chapter/"))
                    directPageListParse(it)
                else
                    seriesJsonPageListParse(it, chapter)
            }

    override fun pageListRequest(chapter: SChapter): Request {
        return if (chapter.url.contains("/chapter/")) {
            GET("$baseUrl${chapter.url}", cubariHeaders)
        } else {
            val u = chapter.url.split("/")
            GET("$baseUrl/read/api/${u[2]}/series/${u[3]}/", cubariHeaders)
        }
    }

    private fun directPageListParse(response: Response): List<Page> {
        val arr = response.parseAs<JsonArray>()
        return arr.mapIndexed { i, el ->
            val src =
                if (el is JsonObject)
                    el.jsonObject["src"]!!.jsonPrimitive.content
                else
                    el.jsonPrimitive.content
            Page(i, "", src)
        }
    }

    private fun seriesJsonPageListParse(response: Response, chapter: SChapter): List<Page> {
        val json = response.parseAs<JsonObject>()

        val groups = json["groups"]!!.jsonObject
        val groupMap = groups.entries.associateBy(
            { it.value.jsonPrimitive.content.ifEmpty { "default" } },
            { it.key }
        )

        val chapters = json["chapters"]!!.jsonObject
            .mapKeys { it.key.replace(Regex("^0+(?!$)"), "") }

        val scan = chapter.scanlator ?: "default"
        val arr =
            chapters[chapter.chapter_number.toString()]
                ?: chapters[chapter.chapter_number.toInt().toString()]

        val pages = arr!!.jsonObject["groups"]!!.jsonObject[groupMap[scan]]!!.jsonArray

        return pages.mapIndexed { i, el ->
            val src =
                if (el is JsonObject)
                    el.jsonObject["src"]!!.jsonPrimitive.content
                else
                    el.jsonPrimitive.content
            Page(i, "", src)
        }
    }

    // ========= SEARCH (HÍBRIDO) =========

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {

        return when {
            // PRIMERO: prefijo híbrido estable
            query.startsWith(PROXY_PREFIX) -> {
                val trimmed = query.removePrefix(PROXY_PREFIX)

                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.TagInterceptor())
                    .build()
                    .newCall(proxyRequest(trimmed))
                    .asObservableSuccess()
                    .map { proxyParse(it, trimmed) }
            }

            // SEGUNDO: intent original desde la Web
            query.startsWith("http") -> {
                val (source, slug) = safeDeepLink(query)
                val tmpQuery = "$source/$slug"

                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.TagInterceptor())
                    .build()
                    .newCall(proxyRequest(tmpQuery))
                    .asObservableSuccess()
                    .map { proxyParse(it, tmpQuery) }
            }

            else -> {
                // búsqueda interna normal
                client.newBuilder()
                    .addInterceptor(RemoteStorageUtils.HomeInterceptor())
                    .build()
                    .newCall(searchMangaRequest(page, query, filters))
                    .asObservableSuccess()
                    .map { searchMangaParse(it, query) }
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/", cubariHeaders)

    private fun proxyRequest(query: String): Request {
        val parts = query.split("/")
        if (parts.size < 2) throw Exception(SEARCH_FALLBACK_MSG)
        return GET("$baseUrl/read/api/${parts[0]}/series/${parts[1]}/", cubariHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    private fun searchMangaParse(response: Response, query: String): MangasPage {
        val arr = response.parseAs<JsonArray>()
        val matches = arr.filter {
            it.jsonObject["title"].toString().contains(query, true)
        }
        return parseMangaList(JsonArray(matches), SortType.ALL)
    }

    private fun proxyParse(response: Response, query: String): MangasPage {
        val obj = response.parseAs<JsonObject>()
        val m = SManga.create().apply { url = "/read/$query" }
        return MangasPage(listOf(parseManga(obj, m)), false)
    }

    // ========= DEEP LINK SEGURO =========

    private fun safeDeepLink(url: String): Pair<String, String> {
        return try {
            deepLinkHandler(url)
        } catch (e: Exception) {
            throw Exception(SEARCH_FALLBACK_MSG)
        }
    }

    private fun deepLinkHandler(query: String): Pair<String, String> {

        if (query.startsWith("cubari:")) {
            val f = query.removePrefix("cubari:").split("/", 2)
            return f[0] to f[1]
        }

        val url = query.toHttpUrl()
        val host = url.host
        val seg = url.pathSegments

        return when {
            host.endsWith("imgur.com") &&
                seg.size >= 2 &&
                seg[0] in listOf("a", "gallery") ->
                "imgur" to seg[1]

            host.endsWith("reddit.com") &&
                seg.size >= 2 &&
                seg[0] == "gallery" ->
                "reddit" to seg[1]

            host == "imgchest.com" &&
                seg.size >= 2 &&
                seg[0] == "p" ->
                "imgchest" to seg[1]

            host.endsWith("catbox.moe") &&
                seg.size >= 2 &&
                seg[0] == "c" ->
                "catbox" to seg[1]

            host.endsWith("cubari.moe") &&
                seg.size >= 3 ->
                seg[1] to seg[2]

            host.endsWith(".githubusercontent.com") -> {
                val src = host.substringBefore(".")
                val path = url.encodedPath
                "gist" to Base64.encodeToString("$src$path".toByteArray(), Base64.NO_PADDING)
            }

            else -> throw Exception(SEARCH_FALLBACK_MSG)
        }
    }

    // ========= HELPERS =========

    private fun parseChapterList(response: Response, manga: SManga): List<SChapter> {
        val json = response.parseAs<JsonObject>()
        val groups = json["groups"]!!.jsonObject
        val chapters = json["chapters"]!!.jsonObject

        return chapters.entries.flatMap { ce ->
            val num = ce.key
            val obj = ce.value.jsonObject
            val cgroups = obj["groups"]!!.jsonObject
            val vol = obj["volume"]!!.jsonPrimitive.content.let {
                if (it in volumeNotSpecifiedTerms) null else it
            }
            val title = obj["title"]!!.jsonPrimitive.content

            cgroups.entries.map { ge ->
                val groupId = ge.key
                val release = obj["release_date"]?.jsonObject?.get(groupId)

                SChapter.create().apply {
                    scanlator = groups[groupId]!!.jsonPrimitive.content
                    chapter_number = num.toFloatOrNull() ?: -1f
                    date_upload = release?.jsonPrimitive?.double?.toLong()?.times(1000) ?: 0L
                    name = buildString {
                        if (!vol.isNullOrEmpty()) append("Vol.$vol ")
                        append("Ch.$num")
                        if (title.isNotBlank()) append(" - $title")
                    }
                    url =
                        if (cgroups[groupId] is JsonArray)
                            "${manga.url}/$num/$groupId"
                        else
                            cgroups[groupId]!!.jsonPrimitive.content
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sort: SortType): MangasPage {
        val list = payload.mapNotNull { el ->
            val obj = el.jsonObject
            val pinned = obj["pinned"]!!.jsonPrimitive.boolean
            when (sort) {
                SortType.PINNED -> if (pinned) parseManga(obj) else null
                SortType.UNPINNED -> if (!pinned) parseManga(obj) else null
                SortType.ALL -> parseManga(obj)
            }
        }
        return MangasPage(list, false)
    }

    private fun parseManga(json: JsonObject, ref: SManga? = null): SManga =
        SManga.create().apply {
            title = json["title"]!!.jsonPrimitive.content
            artist = json["artist"]?.jsonPrimitive?.content ?: ARTIST_FALLBACK
            author = json["author"]?.jsonPrimitive?.content ?: AUTHOR_FALLBACK

            val desc = json["description"]?.jsonPrimitive?.content
            description = desc?.substringBefore("Tags: ") ?: DESCRIPTION_FALLBACK
            genre = desc?.substringAfter("Tags: ", "") ?: ""

            url = ref?.url ?: json["url"]!!.jsonPrimitive.content
            thumbnail_url = json["coverUrl"]?.jsonPrimitive?.content
                ?: json["cover"]?.jsonPrimitive?.content ?: ""
        }

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException()

    companion object {
        const val PROXY_PREFIX = "cubari:"
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Unable to parse Cubari link."
    }

    enum class SortType { PINNED, UNPINNED, ALL }
}
