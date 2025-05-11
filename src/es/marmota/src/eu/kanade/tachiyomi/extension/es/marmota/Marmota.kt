package eu.kanade.tachiyomi.extension.es.marmota

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.multisrc.madara.MadaraFilters
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
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

    override fun getFilterList(): FilterList {
        val original = super.getFilterList()
        val custom = original.filterNot { it is MadaraFilters.GenreConditionFilter }
        return FilterList(custom)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

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
