package eu.kanade.tachiyomi.extension.es.marmota

import eu.kanade.tachiyomi.multisrc.madara.Madara
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

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = super.mangaDetailsParse(document)

    val altNameElement = document.selectFirst(altNameSelector)
    val altNameText = altNameElement?.ownText()

    if (!altNameText.isNullOrBlank() && altNameText.notUpdating()) {
        val formattedAltName = "<b>$altName $altNameText</b>"

        manga.description = when {
            manga.description.isNullOrBlank() -> formattedAltName
            else -> "${manga.description}\n\n$formattedAltName"
        }
    }

    return manga
    }
}
