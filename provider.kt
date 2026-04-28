package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class HdTodayProvider : MainAPI() {
    override var mainUrl = "https://hdtoday.fun"
    override var name = "HDToday"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/tvshows/" to "TV Shows",
        "$mainUrl/trending/" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = Jsoup.connect(url).get()
        
        val items = doc.select("article.item, div.item").map { element ->
            val titleElement = element.selectFirst("h2.title a, h3.title a, a.title")
            val title = titleElement?.text()?.trim() ?: ""
            val href = titleElement?.attr("href") ?: ""
            val posterElement = element.selectFirst("img")
            val poster = posterElement?.attr("data-src") 
                ?: posterElement?.attr("src") 
                ?: ""
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
        
        val nextPage = if (doc.select("a.next").isNotEmpty()) page + 1 else null
        return newHomePageResponse(request.name, items, nextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = Jsoup.connect("$mainUrl/?s=$query").get()
        
        return doc.select("article.item, div.item").map { element ->
            val titleElement = element.selectFirst("h2.title a, h3.title a, a.title")
            val title = titleElement?.text()?.trim() ?: ""
            val href = titleElement?.attr("href") ?: ""
            val posterElement = element.selectFirst("img")
            val poster = posterElement?.attr("data-src") 
                ?: posterElement?.attr("src") 
                ?: ""
            
            val isTvSeries = href.contains("/tvshows/") || 
                element.select("span.tv, span.series").isNotEmpty()
            
            newMovieSearchResponse(title, href, if (isTvSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.connect(url).get()
        
        val title = doc.selectFirst("h1.title, h1.entry-title, h1[itemprop=name]")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.poster, img.attachment-post-thumbnail")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        
        val description = doc.selectFirst("div.description, div.entry-content, div[itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst("span.year, span.release-date, meta[itemprop=dateCreated]")?.let {
            it.text().trim().substringBefore("-").trim()
        }?.toIntOrNull()
        
        val tags = doc.select("span.genre a, a.genre, span.category a").map { it.text().trim() }
        
        val type = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie
        
        return if (type == TvType.TvSeries) {
            val episodes = doc.select("ul.episodes li, div.seasons div.episode, div.episodes-list a").map { ep ->
                val epTitle = ep.selectFirst("span.title, span.ep-title")?.text()?.trim() ?: ""
                val epUrl = ep.selectFirst("a")?.attr("href") ?: ""
                
                val seasonEpisode = extractSeasonEpisode(ep.text(), epTitle)
                Episode(epUrl, seasonEpisode?.second ?: epTitle, seasonEpisode?.first)
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.connect(data).get()
        
        // Iframe'leri bul
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        // Video kaynaklarını bul (player bölümünde)
        doc.select("div.player-container script, script").forEach { script ->
            val html = script.html()
            // video URL pattern'lerini ara
            val patterns = listOf(
                """"file":"([^"]+)"""",
                """file:\s*"([^"]+)"""",
                """source:\s*"([^"]+)"""",
                """videoUrl:\s*"([^"]+)"""",
                """"src":"([^"]+\.mp4[^"]*)"""",
                """src:\s*"([^"]+\.mp4[^"]*)"""",
            )
            
            patterns.forEach { pattern ->
                Regex(pattern).findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    Quality.fromUrl(videoUrl)?.let { quality ->
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                data,
                                quality,
                                true
                            )
                        )
                    }
                }
            }
        }
        
        return true
    }

    private fun Quality.Companion.fromUrl(url: String): Int? {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }

    private fun extractSeasonEpisode(text: String, title: String): Pair<Int?, String?>? {
        val patterns = listOf(
            Regex("""Season\s*(\d+)\s*Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)[\s-]*E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)x(\d+)"""),
            Regex("""Episode\s*(\d+)"""),
        )
        
        patterns.forEach { pattern ->
            pattern.find(text.plus(" ").plus(title))?.let {
                val groups = it.groupValues
                if (groups.size == 3) {
                    return Pair(groups[1].toIntOrNull(), groups[2].toIntOrNull())
                } else if (groups.size == 2) {
                    return Pair(1, groups[1].toIntOrNull())
                }
            }
        }
        return null
    }
}
