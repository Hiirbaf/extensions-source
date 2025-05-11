package eu.kanade.tachiyomi.extension.es.marmota

import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga

class Marmota : Madara(
    "Marmota",
    "https://marmota.me",
    "es",
    dateFormat = SimpleDateFormat("d 'de' MMM 'de' yyyy", Locale("es")),
) {
    override val mangaSubString: String = "comic"
    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // Custom search implementation to remove dependence on genre conditions
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comic/".toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<GenreFilter>()
                .firstOrNull()
                ?.addFilterToUrl(this)

            if (page > 1) {
                addPathSegments("page/$page/")
            }
        }.build()

        return GET(url, headers)
    }

    override fun getFilterList(): FilterList {
        val filters = super.getFilterList().filters.filterNot { filter ->
            filter is GenreConditionFilter
        }

        return FilterList(filters)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        // Ensure "Comic" is added to genres if not already present
        val genres = manga.genre.orEmpty()
            .split(", ")
            .map { it.trim() }
            .toMutableList()

        if (genres.none { it.equals("comic", ignoreCase = true) }) {
            genres.add("Comic")
        }

        manga.genre = genres.joinToString(", ")
        return manga
    }
}
