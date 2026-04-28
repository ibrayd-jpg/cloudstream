package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Element
import java.net.URLDecoder

class HDTodayProvider : MainAPI() {
    override var mainUrl = "https://hdtoday.tv" // veya hdtoday.fun
    override var name = "HDToday"
    override val hasQuickSearch = false
    override val hasMainPage = true

    // 1. Ana sayfadaki filmler (örnek: popular / latest)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page").document

        val items = document.select("div.movie-item, .item-post") // site HTML’ine göre düzenle
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Latest Movies", items)),
            hasNext = page < 20 // sayfa sınırlıysa
        )
    }

    // 2. HTML öğesini SearchResponse’a çevir
    private fun Element.toSearchResult(): SearchResponse? {
        val id = this.selectFirst("a[href*="/movie/"]")?.attr("href")
            ?: this.selectFirst("a[href*="/tv/"]")?.attr("href") ?: return null

        val title = this.selectFirst(".title, .movie-title")?.text().trim()
            ?: this.selectFirst("a[href*="/movie/"]")?.attr("title")
            ?: this.selectFirst("a[href*="/tv/"]")?.text().trim()
            ?: return null

        val poster = this.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

        return newMovieSearchResponse(
            title,
            id, // URL’nin son kısmı
            if (id.contains("/tv/")) TvType.TvSeries else TvType.Movie
        ).also {
            it.posterUrl = poster
        }
    }

    // 3. Arama (isteğe bağlı; site API kullanıyorsa)
    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val res = ArrayList<SearchResponse>()
        val encoded = query.encodeToUrlComponent()
        val document = app.get("$mainUrl/search?keyword=$encoded").document

        document.select("div.movie-item, .item-post") // aynı CSS seçici
            .mapNotNull { it.toSearchResult() }
            .forEach { res.add(it) }

        return res
    }

    // 4. Film sayfası → oynatma linkleri
    override suspend fun load(url: String): LoadResponse {
        val fullUrl = fixUrlReturn(url)
        val document = app.get(fullUrl).document

        val title = document.selectFirst("h1, .movie-title")?.text().trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("img.poster, .movie-img")?.attr("src")
            ?.let { fixUrl(it) }

        val yearText = document.selectFirst(".year")?.text()
            ?.trim()
            ?.let { Regex("""d{4}""").find(it)?.value }?.toIntOrNull()

        val isTv = url.contains("/tv/")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            document.select("div.episode-item, .episode") // seçici site HTML’ine göre
                .forEach { epElem ->
                    val epUrl = epElem.selectFirst("a[href]")?.attr("href") ?: return@forEach
                    val epName = epElem.selectFirst(".title, .ep-name")?.text()?.trim()
                        ?: "Episode ${episodes.size + 1}"

                    val epNum = Regex("""d+""").find(epName)?.value?.toIntOrNull()

                    episodes.add(
                        Episode(
                            url = epUrl,
                            name = epName,
                            season = 1, // site yapıya göre sezonlar varsa ayrıştır
                            episode = epNum ?: episodes.size + 1
                        )
                    )
                }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ).also {
                it.year = yearText
                it.posterUrl = poster
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url = fullUrl
            ).also {
                it.year = yearText
                it.posterUrl = poster
            }
        }
    }

    // 5. Video linkini çıkar (cloudflare, script, vs. varsa)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Örnek: video iframe / direct mp4 / .m3u8
        var url: String? = null

        // .m3u8 / adaptive stream
        document.select("video source[src*=".m3u8"]")
            .mapNotNull { it.attr("src") }
            .firstOrNull()
            ?.let {
                url = it
            }

        // Veya direkt mp4
        if (url == null) {
            document.select("video source[src*=".mp4"]")
                .mapNotNull { it.attr("src") }
                .firstOrNull()
                ?.let {
                    url = it
                }
        }

        // Eğer iframe içinden link çıkıyorsa (örnek: <iframe src=".../embed?url=...") burayı genişlet
        // Burada sade bir durum varsayalım:
        if (url != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "HDToday",
                    url = fixUrlReturn(url!!),
                    type = if (url!!.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.M3U8, // HLS/MP4
                    quality = Qualities.Unknown.value,
                    headers = mapOf("Referer" to data)
                )
            )
            return true
        }

        // Alternatif: iframe varsa içine girip url çıkar
        document.select("iframe[src]").mapNotNull { iframe ->
            val iframeUrl = iframe.attr("src")
            val iframeDoc = app.get(iframeUrl).document

            iframeDoc.select("video source[src*=".m3u8"], video source[src*=".mp4"]")
                .mapNotNull { src -> src.attr("src") }
                .firstOrNull()
                ?.let {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "HDToday ($iframeUrl)",
                            url = fixUrlReturn(it),
                            type = if (it.contains(".m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.M3U8,
                            quality = Qualities.Unknown.value,
                            headers = mapOf("Referer" to iframeUrl)
                        )
                    )
                }
        }

        return true
    }

    // 6. Helper: URL düzeltme
    private fun fixUrlReturn(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }
}
