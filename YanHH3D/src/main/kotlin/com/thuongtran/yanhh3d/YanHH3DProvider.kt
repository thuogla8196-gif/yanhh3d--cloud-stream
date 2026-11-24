package com.thuongtran.yanhh3d

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class YanHH3DProvider : MainAPI() {
    override var mainUrl = "https://yanhh3d.ee"
    override var name = "YanHH3D"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Mới cập nhật"
    )

    private fun Element.toAnime(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
        if (!href.startsWith(mainUrl) || href == mainUrl || href.contains("/tap-")) return null
        val title = (selectFirst("h3, h2, .film-name, .title, .name")?.text()
            ?: anchor.attr("title").ifBlank { anchor.text() }).trim()
        if (title.isBlank()) return null
        val image = selectFirst("img")
        val poster = image?.absUrl("data-src")?.ifBlank { image.absUrl("src") }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(searchUrl).document
        val cards = doc.select("article, .flw-item, .item, .film-item, .movie-item, .list-item, .anime-item")
        val results = cards.mapNotNull { it.toAnime() }.distinctBy { it.url }
        if (results.isNotEmpty()) return results
        return doc.select("a[href]").mapNotNull { a ->
            if (a.selectFirst("img") != null) a.toAnime() else null
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = app.get(pageUrl).document
        val results = doc.select("article, .flw-item, .item, .film-item, .movie-item, .list-item, .anime-item")
            .mapNotNull { it.toAnime() }.distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: doc.title()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".poster img, .film-poster img, img")?.absUrl("src")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".description, .film-description, .content-detail")?.text()
        val tags = doc.select(".genre a, .genres a, a[href*=the-loai]")
            .map { it.text() }.filter { it.isNotBlank() }.distinct()
        val episodes = doc.select("a[href]").mapNotNull { a ->
            val label = a.text().trim()
            val href = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
            if (!label.matches(Regex("(?i).*(tập|episode|ep)\\s*[-:]?\\s*\\d+.*")) ||
                !href.startsWith(mainUrl)) return@mapNotNull null
            val number = Regex("(?i)(?:tập|episode|ep)\\s*[-:]?\\s*(\\d+)")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                name = label
                episode = number
            }
        }.distinctBy { it.data }
        return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            showStatus = ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("Referer" to mainUrl)).document
        val frames = doc.select("iframe[src], iframe[data-src]").mapNotNull { frame ->
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            fixUrl(raw).takeIf { it.startsWith("http") }
        }.distinct()
        frames.forEach { frame ->
            loadExtractor(frame, data, subtitleCallback, callback)
        }
        doc.select("video source[src], video[src]").forEach { source ->
            val raw = source.attr("src")
            val videoUrl = fixUrl(raw)
            if (videoUrl.startsWith("http")) {
                callback.invoke(newExtractorLink(name, name, videoUrl) {
                    referer = data
                })
            }
        }
        return frames.isNotEmpty() || doc.select("video[src], video source[src]").isNotEmpty()
    }
}
