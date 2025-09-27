package eu.kanade.tachiyomi.extension.ko.rawdex

import eu.kanade.tachiyomi.multisrc.madara.Madara

class RawDEX : Madara(
    "RawDEX",
    "https://rawdex.net",
    "ko",
) {
    override val mangaSubString: String = "manga"
    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Never
}
