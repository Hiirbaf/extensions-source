package eu.kanade.tachiyomi.extension.es.ikigaimangas

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.lib.cookieinterceptor.CookieInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class IkigaiMangas : HttpSource(), ConfigurableSource {

    private val isCi = System.getenv("CI") == "true"

    override val baseUrl: String
        get() = if (isCi) defaultBaseUrl else preferences.prefBaseUrl

    private val defaultBaseUrl = "https://ikigaitoon.bookir.net"
    private val apiBaseUrl = "https://panel.ikigaimangas.com"

    override val lang = "es"
    override val name = "Ikigai Mangas"
    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences()

    private val cookieInterceptor = CookieInterceptor(
        "",
        listOf("nsfw-mode" to "true"),
    )

    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimitHost(baseDomainUrl.toHttpUrl(), 1, 2)
            .rateLimitHost(apiBaseUrl.toHttpUrl(), 2, 1)
            .addNetworkInterceptor(cookieInterceptor)
            .build()
    }

    private val lazyHeaders by lazy {
        headersBuilder()
            .set("Referer", baseDomainUrl)
            .build()
    }

    private val json: Json by injectLazy()

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

    /**
     * Base domain con fallback al default.
     */
    private val baseDomainUrl: String
        get() = preferences.prefBaseUrl.ifEmpty { defaultBaseUrl }

    // -----------------------------
    // Requests
    // -----------------------------

    override fun popularMangaRequest(page: Int): Request {
        val apiUrl = "$apiBaseUrl/api/swf/series/ranking-list".toHttpUrl().newBuilder()
            .addQueryParameter("type", "total_ranking")
            .addQueryParameter("series_type", "comic")
            .addQueryParameter("nsfw", preferences.showNsfwPref.toString())
            .build()

        return GET(apiUrl, lazyHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage = response.use {
        val result = json.decodeFromString<PayloadSeriesDto>(it.body.string())
        val mangaList = result.data.map { dto -> dto.toSManga() }
        MangasPage(mangaList, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val apiUrl = "$apiBaseUrl/api/swf/new-chapters".toHttpUrl().newBuilder()
            .addQueryParameter("nsfw", preferences.showNsfwPref.toString())
            .addQueryParameter("page", page.toString())
            .build()

        return GET(apiUrl, lazyHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = response.use {
        val result = json.decodeFromString<PayloadLatestDto>(it.body.string())
        val mangaList = result.data.filter { dto -> dto.type == "comic" }.map { it.toSManga() }
        MangasPage(mangaList, result.hasNextPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sortByFilter = filters.firstInstanceOrNull<SortByFilter>()
        val apiUrl = "$apiBaseUrl/api/swf/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("type", "comic")
            .addQueryParameter("nsfw", preferences.showNsfwPref.toString())

        if (query.isNotEmpty()) apiUrl.addQueryParameter("search", query)

        filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
            .filter(Genre::state)
            .map(Genre::id)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.let { apiUrl.addQueryParameter("genres", it) }

        filters.firstInstanceOrNull<StatusFilter>()?.state.orEmpty()
            .filter(Status::state)
            .map(Status::id)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.let { apiUrl.addQueryParameter("status", it) }

        apiUrl.addQueryParameter("column", sortByFilter?.selected ?: "name")
        apiUrl.addQueryParameter("direction", if (sortByFilter?.state?.ascending == true) "asc" else "desc")

        return GET(apiUrl.build(), lazyHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = response.use {
        val result = json.decodeFromString<PayloadSeriesDto>(it.body.string())
        val mangaList = result.data.filter { dto -> dto.type == "comic" }.map { it.toSManga() }
        MangasPage(mangaList, result.hasNextPage())
    }

    override fun getMangaUrl(manga: SManga): String =
        baseDomainUrl + manga.url.substringBefore("#").replace("/series/comic-", "/series/")

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/series/comic-").substringBefore("#")
        return GET("$apiBaseUrl/api/swf/series/$slug", lazyHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.use {
        val result = json.decodeFromString<PayloadSeriesDetailsDto>(it.body.string())
        result.series.toSMangaDetails()
    }

    override fun getChapterUrl(chapter: SChapter): String =
        baseDomainUrl + chapter.url.substringBefore("#")

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/series/comic-").substringBefore("#")
        return GET("$apiBaseUrl/api/swf/series/$slug/chapters?page=1", lazyHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.toString()
            .substringAfter("/series/")
            .substringBefore("/chapters")

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasNext: Boolean

        do {
            val res = client.newCall(
                GET("$apiBaseUrl/api/swf/series/$slug/chapters?page=$page", lazyHeaders),
            ).execute()

            res.use {
                val result = json.decodeFromString<PayloadChaptersDto>(it.body.string())
                chapters += result.data.map { dto -> dto.toSChapter(dateFormat) }
                hasNext = result.meta.hasNextPage()
            }

            page++
        } while (hasNext)

        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseDomainUrl + chapter.url.substringBefore("#"), lazyHeaders)

    override fun pageListParse(response: Response): List<Page> = response.use {
        val document = it.asJsoup()
        document.select("section div.img > img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // -----------------------------
    // Filtros
    // -----------------------------

    private var genresList: List<Pair<String, Long>> = emptyList()
    private var statusesList: List<Pair<String, Long>> = emptyList()
    private var fetchFiltersAttempts = 0
    private var filtersState = FiltersState.NOT_FETCHED

    override fun getFilterList(): FilterList {
        fetchFilters()

        val filters = mutableListOf<Filter<*>>(SortByFilter("Ordenar por", getSortProperties()))

        filters += if (filtersState == FiltersState.FETCHED) {
            listOf(
                StatusFilter("Estados", getStatusFilters()),
                GenreFilter("Géneros", getGenreFilters()),
            )
        } else {
            listOf(Filter.Header("Presione 'Restablecer' para intentar cargar los filtros"))
        }

        return FilterList(filters)
    }

    private fun getSortProperties(): List<SortProperty> = listOf(
        SortProperty("Nombre", "name"),
        SortProperty("Creado en", "created_at"),
        SortProperty("Actualización más reciente", "last_chapter_date"),
        SortProperty("Número de favoritos", "bookmark_count"),
        SortProperty("Número de valoración", "rating_count"),
        SortProperty("Número de vistas", "view_count"),
    )

    private fun getGenreFilters(): List<Genre> = genresList.map { Genre(it.first, it.second) }
    private fun getStatusFilters(): List<Status> = statusesList.map { Status(it.first, it.second) }

    private fun fetchFilters() {
        if (filtersState != FiltersState.NOT_FETCHED || fetchFiltersAttempts >= 3) return
        filtersState = FiltersState.FETCHING
        fetchFiltersAttempts++

        client.newCall(
            GET(
                "$apiBaseUrl/api/swf/filter-options",
                lazyHeaders,
            ),
        )
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    filtersState = FiltersState.NOT_FETCHED
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val filters = json.decodeFromString<PayloadFiltersDto>(it.body.string())
                            genresList = filters.data.genres.map { g -> g.name.trim() to g.id }
                            statusesList = filters.data.statuses.map { s -> s.name.trim() to s.id }
                            filtersState = FiltersState.FETCHED
                        } catch (_: Throwable) {
                            filtersState = FiltersState.NOT_FETCHED
                        }
                    }
                }
            },
            )
    }

    // -----------------------------
    // Preferencias
    // -----------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_NSFW_PREF
            title = SHOW_NSFW_PREF_TITLE
            setDefaultValue(SHOW_NSFW_PREF_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                _cachedNsfwPref = newValue as Boolean
                true
            }
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = FETCH_DOMAIN_PREF_TITLE
            summary = FETCH_DOMAIN_PREF_SUMMARY
            setDefaultValue(FETCH_DOMAIN_PREF_DEFAULT)
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "URL por defecto:\n$defaultBaseUrl"
            setDefaultValue(defaultBaseUrl)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    private fun SharedPreferences.fetchDomainPref() =
        getBoolean(FETCH_DOMAIN_PREF, FETCH_DOMAIN_PREF_DEFAULT)

    private var _cachedBaseUrl: String? = null
    private var SharedPreferences.prefBaseUrl: String
        get() {
            if (_cachedBaseUrl == null) {
                _cachedBaseUrl = getString(BASE_URL_PREF, defaultBaseUrl) ?: defaultBaseUrl
            }
            return _cachedBaseUrl!!
        }
        set(value) {
            _cachedBaseUrl = value
            edit().putString(BASE_URL_PREF, value).apply()
        }

    private var _cachedNsfwPref: Boolean? = null
    private var SharedPreferences.showNsfwPref: Boolean
        get() {
            if (_cachedNsfwPref == null) {
                _cachedNsfwPref = getBoolean(SHOW_NSFW_PREF, SHOW_NSFW_PREF_DEFAULT)
            }
            return _cachedNsfwPref!!
        }
        set(value) {
            _cachedNsfwPref = value
            edit().putBoolean(SHOW_NSFW_PREF, value).apply()
        }

    private inline fun <reified R> List<*>.firstInstanceOrNull(): R? =
        filterIsInstance<R>().firstOrNull()

    private enum class FiltersState { NOT_FETCHED, FETCHING, FETCHED }

    companion object {
        private const val SHOW_NSFW_PREF = "pref_show_nsfw"
        private const val SHOW_NSFW_PREF_TITLE = "Mostrar contenido NSFW"
        private const val SHOW_NSFW_PREF_DEFAULT = false

        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Editar URL de la fuente"
        private const val BASE_URL_PREF_SUMMARY =
            "Para uso temporal, si la extensión se actualiza se perderá el cambio."
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val RESTART_APP_MESSAGE = "Reinicie la aplicación para aplicar los cambios"

        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_TITLE = "Buscar dominio automáticamente"
        private const val FETCH_DOMAIN_PREF_SUMMARY =
            "Intenta buscar el dominio automáticamente al abrir la fuente."
        private const val FETCH_DOMAIN_PREF_DEFAULT = true
    }

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { domain ->
            if (domain != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }
}
