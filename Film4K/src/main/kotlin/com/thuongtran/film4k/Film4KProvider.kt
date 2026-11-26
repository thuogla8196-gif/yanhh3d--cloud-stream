package com.thuongtran.film4k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Film4KProvider : MainAPI() {
    override var mainUrl = "https://film4k.pl"
    override var name = "Film4K"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/api/home" to "Trang chủ")

    private fun JSONObject.localized(vararg keys: String): String {
        for (key in keys) {
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is JSONObject) {
                val text = value.optString("vi").ifBlank { value.optString("en") }
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun JSONObject.poster(): String {
        val direct = localized("posterUrl", "image")
        if (direct.startsWith("http")) return direct
        return optJSONObject("poster")?.localized("vi", "en") ?: ""
    }

    private fun collect(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                if (value.optString("slug").isNotBlank() && value.localized("title", "name").isNotBlank()) out.add(value)
                val keys = value.keys()
                while (keys.hasNext()) collect(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), out)
        }
    }

    private fun parseTitles(json: String): List<JSONObject> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val found = mutableListOf<JSONObject>()
        collect(root, found)
        return found.distinctBy { it.optString("slug") }
    }

    private fun toSearch(item: JSONObject): SearchResponse? {
        val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = item.localized("title", "name").ifBlank { return null }
        val year = item.optInt("year", 0).takeIf { it > 0 }?.let { " ($it)" } ?: ""
        val type = if (item.optString("mediaType") == "tv" || item.optString("type") == "series") TvType.TvSeries else TvType.Movie
        return newAnimeSearchResponse(title + year, "$mainUrl/watch/$slug", type) {
            posterUrl = item.poster().takeIf { it.startsWith("http") }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val endpoint = "$mainUrl/api/search/suggest?q=" + URLEncoder.encode(query, "UTF-8")
        val json = app.get(endpoint, headers = mapOf("Referer" to "$mainUrl/")).text
        return parseTitles(json).mapNotNull { toSearch(it) }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val endpoint = request.data + if (page > 1) "?page=$page" else ""
        val json = app.get(endpoint, headers = mapOf("Referer" to "$mainUrl/")).text
        val results = parseTitles(json).mapNotNull { toSearch(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        require(slug.isNotBlank()) { "Không tìm thấy mã phim Film4K" }
        val endpoint = "$mainUrl/api/watch/" + URLEncoder.encode(slug, "UTF-8")
        val root = JSONObject(app.get(endpoint, headers = mapOf("Referer" to "$mainUrl/watch/$slug")).text)
        val movie = root.optJSONObject("movie") ?: root
        val title = movie.localized("title", "name").ifBlank { slug }
        val overview = movie.localized("overview", "description", "plot")
        val poster = movie.poster()
        val backdrop = movie.optString("backdrop").takeIf { it.startsWith("http") }
        val year = movie.optInt("year", 0).takeIf { it > 0 }
        val tags = mutableListOf<String>()
        val genreObject = movie.optJSONObject("genres")
        val genreArray = genreObject?.optJSONArray("vi") ?: genreObject?.optJSONArray("en")
        if (genreArray != null) for (i in 0 until genreArray.length()) genreArray.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }

        val episodeArray = root.optJSONArray("episodes") ?: JSONArray()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until episodeArray.length()) {
            val ep = episodeArray.optJSONObject(i) ?: continue
            val sources = ep.optJSONArray("sources") ?: JSONArray()
            val urls = mutableListOf<String>()
            for (j in 0 until sources.length()) {
                val source = sources.optJSONObject(j) ?: continue
                val raw = source.optString("url")
                if (raw.isNotBlank()) urls.add(if (raw.startsWith("http")) raw else "$mainUrl$raw")
            }
            if (urls.isEmpty()) continue
            val number = ep.optInt("episode", i + 1)
            episodes.add(newEpisode(urls.joinToString("|")) {
                name = ep.optString("title").ifBlank { "Tập $number" }
                season = ep.optInt("season", 1)
                episode = number
                posterUrl = ep.optString("still").takeIf { it.startsWith("http") }
                description = ep.optString("overview").takeIf { it.isNotBlank() }
            })
        }

        if (movie.optString("mediaType") == "tv" || episodes.isNotEmpty()) {
            return newAnimeLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster.takeIf { it.startsWith("http") }
                backgroundPosterUrl = backdrop
                plot = overview
                this.tags = tags
                year?.let { this.year = it }
            }
        }

        val sourceArray = movie.optJSONArray("sources") ?: JSONArray()
        val movieUrls = mutableListOf<String>()
        for (i in 0 until sourceArray.length()) {
            val source = sourceArray.optJSONObject(i) ?: continue
            val raw = source.optString("url")
            if (raw.isNotBlank()) movieUrls.add(if (raw.startsWith("http")) raw else "$mainUrl$raw")
        }
        return newMovieLoadResponse(title, url, TvType.Movie, movieUrls.joinToString("|")) {
            posterUrl = poster.takeIf { it.startsWith("http") }
            backgroundPosterUrl = backdrop
            plot = overview
            this.tags = tags
            year?.let { this.year = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split("|").map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false
        urls.forEachIndexed { index, videoUrl ->
            if (videoUrl.contains(".m3u8") || videoUrl.contains("/api/hls/")) {
                callback.invoke(newExtractorLink(name, "Film4K · Server \${index + 1}", videoUrl) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                })
            } else {
                loadExtractor(videoUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}
