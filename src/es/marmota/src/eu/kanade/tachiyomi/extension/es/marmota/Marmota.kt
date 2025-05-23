package eu.kanade.tachiyomi.extension.es.marmota

import eu.kanade.tachiyomi.multisrc.madara.Madara
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
    override val altNameSelector = ".post-content_item:contains(Alt) .summary-content"
    override val altName = "Nombre alternativo"
    override val updatingRegex = "Updating|Atualizando".toRegex(RegexOption.IGNORE_CASE)

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        val altNameElement = document.selectFirst(altNameSelector)
        val altNameText = altNameElement?.ownText()

        if (!altNameText.isNullOrBlank() && !altNameText.contains(updatingRegex)) {
            val formattedAltName = "**$altName $altNameText**"

            manga.description = when {
                manga.description.isNullOrBlank() -> formattedAltName
                else -> "${manga.description}\n\n$formattedAltName"
            }
        }

        return manga
    }
}
