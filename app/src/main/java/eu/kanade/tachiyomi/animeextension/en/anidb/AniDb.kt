package eu.kanade.tachiyomi.animeextension.en.anidb

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.lang.Exception

class AniDb : AnimeHttpSource() {

    override val name = "AniDB"

    override val baseUrl = "https://anidb.app"

    override val lang = "en"

    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/browse?q=&type=&status=&season=&year=&genres=&sort=order_top_airing&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val results = mutableListOf<SAnime>()
        document.select("a.anime-card").forEach { item ->
            val title = item.attr("title")
            val url = item.attr("href") // e.g. /anime/slug-12345
            val posterUrl = item.selectFirst("img")?.attr("src")
            val anime = SAnime.create().apply {
                this.title = title
                this.url = url.substringAfter(baseUrl).removePrefix("/")
                this.thumbnail_url = posterUrl
            }
            results.add(anime)
        }
        return AnimesPage(results, results.size >= 20)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/browse?q=&type=&status=&season=&year=&genres=&sort=order_updated&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/browse?q=$query&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: document.selectFirst(".description")?.text()
            thumbnail_url = document.selectFirst("div.flex-shrink-0 img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            genre = document.select("a.filter-chip").joinToString(", ") { it.text() }
            
            val statusText = document.selectFirst("a[class*=badge][href*=/browse?status=]")?.text()
            status = when (statusText) {
                "Finished Airing" -> SAnime.COMPLETED
                "Currently Airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        val siteId = slug.substringAfterLast("-").toIntOrNull()
            ?: throw Exception("Could not parse site ID from ${anime.url}")
        return GET("$baseUrl/api/frontend/anime/$siteId/episodes", Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonStr = response.body.string()
        val jsonObj = JSONObject(jsonStr)
        val episodesArr = jsonObj.optJSONArray("episodes") ?: JSONArray()
        val epList = mutableListOf<SEpisode>()
        
        val slug = response.request.url.pathSegments.let { it.getOrNull(it.size - 2) } ?: ""
        
        for (i in 0 until episodesArr.length()) {
            val epItem = episodesArr.getJSONObject(i)
            val id = epItem.optInt("id")
            val num = epItem.optDouble("number", (i + 1).toDouble()).toFloat()
            val filler = epItem.optBoolean("filler", false)
            
            val episode = SEpisode.create().apply {
                this.url = "api/frontend/episode/$id/languages?slug=$slug&num=$num"
                this.name = "Episode ${num.toInt()}"
                this.episode_number = num
                this.fillermark = filler
            }
            epList.add(episode)
        }
        
        return epList.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = "$baseUrl/${episode.url}"
        return GET(url, Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build())
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonStr = response.body.string()
        val jsonObj = JSONObject(jsonStr)
        val languagesArr = jsonObj.optJSONArray("languages") ?: JSONArray()
        val videos = mutableListOf<Video>()
        
        val hlsRegex = listOf(
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+/master\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        
        for (i in 0 until languagesArr.length()) {
            val langObj = languagesArr.getJSONObject(i)
            val code = langObj.optString("code", "").lowercase()
            val nameStr = langObj.optString("name", "")
            val embedUrl = langObj.optString("embed_url", "")
            
            if (embedUrl.isEmpty()) continue
            
            val isDub = code in listOf("eng", "en", "english") || nameStr.lowercase() in listOf("eng", "en", "english")
            val audioType = if (isDub) "Dub" else "Sub"
            
            try {
                val embedResponse = client.newCall(GET(embedUrl, Headers.Builder().add("Referer", "$baseUrl/").build())).execute()
                val embedDoc = embedResponse.body.string()
                
                var hlsUrl: String? = null
                for (regex in hlsRegex) {
                    val match = regex.find(embedDoc)
                    if (match != null) {
                        hlsUrl = match.groupValues[1]
                        break
                    }
                }
                
                if (hlsUrl != null) {
                    val videoHeaders = Headers.Builder()
                        .add("Referer", "$baseUrl/")
                        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    
                    videos.add(
                        Video(
                            videoUrl = hlsUrl,
                            videoTitle = "$nameStr ($audioType)",
                            headers = videoHeaders
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return videos
    }
}
