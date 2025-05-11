package eu.kanade.tachiyomi.extension.es.marmota

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class Marmota : Madara(
    "Marmota",
    "https://marmota.me",
    "es",
    dateFormat = SimpleDateFormat("d 'de' MMM 'de' yyyy", Locale("es")),
) {
    override val mangaSubString: String = "comic"
    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // Modificación en la búsqueda para no depender de la condición de género
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comic/".toHttpUrl().newBuilder().apply {
            // Agregamos el filtro de género sin necesidad de que haya uno seleccionado
            val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
            genreFilter?.addFilterToUrl(this)

            if (page > 1) {
                addPathSegments("page/$page/")
            }
        }.build()

        return GET(url, headers)
    }

    override fun getFilterList(): FilterList {
        val filters = super.getFilterList().filters.filterNot { filter ->
            // Elimina la condición de género
            filter is GenreConditionFilter
        }

        return FilterList(filters = filters)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        // Modificar los géneros para asegurarnos de que "Comic" se agrega correctamente
        val genres = manga.genre.orEmpty()
            .split(", ")
            .map { it.trim() }
            .toMutableList()

        if (genres.none { it.contains("comic", ignoreCase = true) }) {
            genres.add("Comic")
        }

        manga.genre = genres.joinToString(", ")
        return manga
    }
}
