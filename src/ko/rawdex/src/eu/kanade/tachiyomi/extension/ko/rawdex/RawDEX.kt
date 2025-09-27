package eu.kanade.tachiyomi.extension.ko.rawdex

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class RawDEX : Madara(
    "RawDEX",
    "https://rawdex.net",
    "ko",
    dateFormat = SimpleDateFormat("d 'de' MMM 'de' yyyy", Locale("ko")),
) {
    override val mangaSubString: String = "manhwa"
    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Never
}
