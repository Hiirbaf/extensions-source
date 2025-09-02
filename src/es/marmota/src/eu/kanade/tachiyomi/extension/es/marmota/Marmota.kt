package eu.kanade.tachiyomi.extension.es.marmota

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.multisrc.madara.Madara.LoadMoreStrategy
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

    override fun mangaDetailsParse(document: Document): SManga {
        return super.mangaDetailsParse(document).apply {
            // 👇 Forzamos tipo cómic para que Yōkai lo abra con modo cómic (LTR)
            type = SManga.TYPE_COMIC
        }
    }
}
