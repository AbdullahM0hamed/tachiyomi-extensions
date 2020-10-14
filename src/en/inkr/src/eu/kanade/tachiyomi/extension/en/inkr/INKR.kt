package eu.kanade.tachiyomi.extension.en.inkr

import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.jsonArray
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.set
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.ArrayList
import kotlin.experimental.and
import kotlin.experimental.xor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Headers
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.Locale
import rx.Observable

/**
 * INKR source - same old MR code, though
 */

class INKR : HttpSource() {

    override val name = "INKR"

    override val baseUrl = "https://mangarock.com"

    private val apiUrl = "https://icd-api.inkr.com/v1/content_json"

    private val titlesApiUrl = "https://icq-api.inkr.com/v1/title/filtered"

    private val gson = Gson()

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("ikc-platform", "android-beta")

    // Handles the page decoding
    override val client: OkHttpClient = super.client.newBuilder().addInterceptor(fun(chain): Response {
        val url = chain.request().url().toString()
        val response = chain.proceed(chain.request())
        if (!url.endsWith("w1600.ikc")) return response

        val decoded: ByteArray = decodeMri(response)
        val mediaType = MediaType.parse("image/webp")
        val rb = ResponseBody.create(mediaType, decoded)
        return response.newBuilder().body(rb).build()
    }).build()

    override fun latestUpdatesRequest(page: Int): Request {
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")

        val body = RequestBody.create(jsonType, jsonObject(
                "order" to "latest"
        ).toString())

        return POST(titlesApiUrl, headers, body)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun popularMangaRequest(page: Int): Request {
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")

        val body = RequestBody.create(jsonType, jsonObject(
                "order" to "name"
        ).toString())

        return POST(titlesApiUrl, headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val idArray = gson.fromJson<JsonObject>(response.body()!!.string())["data"] as JsonArray

        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")
        val fields = jsonArray(listOf(
            "oid",
            "name",
            "thumbnailURL"
        ))
        val body = RequestBody.create(jsonType, jsonObject(
            "fields" to fields,
            "oids" to idArray
        ).toString())

        val metaRes = client.newCall(POST(apiUrl, headers, body)).execute().body()!!.string()

        val res = JSONObject(metaRes).getJSONObject("data")
        val mangas = ArrayList<SManga>(res.length())

        idArray.forEach {
            mangas.add(parseMangaJson(res.getJSONObject(it.toString().replace("\"", ""))))
        }

        return MangasPage(mangas, false)
    }


    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")

        // Filter
        if (query.isBlank()) {
            var status = ""
            var orderBy = ""
            val genres = jsonObject()
            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> {
                        status = when (filter.state) {
                            Filter.TriState.STATE_INCLUDE -> "completed"
                            Filter.TriState.STATE_EXCLUDE -> "ongoing"
                            else -> "all"
                        }
                    }
                    is SortBy -> {
                        orderBy = filter.toUriPart()
                    }
                    is GenreList -> {
                        filter.state
                                .filter { it.state != Filter.TriState.STATE_IGNORE }
                                .forEach { genres[it.id] = it.state == Filter.TriState.STATE_INCLUDE }
                    }
                }
            }

            val body = RequestBody.create(jsonType, jsonObject(
                    "status" to status,
                    "genres" to genres,
                    "order" to orderBy
            ).toString())
            return POST("$apiUrl/mrs_filter", headers, body)
        }

        // Regular search
        val body = RequestBody.create(jsonType, jsonObject(
                "type" to "series",
                "keywords" to query
        ).toString())
        return POST("$apiUrl/mrs_search", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val idArray = JSONObject(response.body()!!.string()).getJSONArray("data")

        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")
        val body = RequestBody.create(jsonType, idArray.toString())
        val metaRes = client.newCall(POST("https://api.mangarockhd.com/meta", headers, body)).execute().body()!!.string()

        val res = JSONObject(metaRes).getJSONObject("data")
        val mangas = ArrayList<SManga>(res.length())
        for (i in 0 until idArray.length()) {
            val id = idArray.get(i).toString()
            mangas.add(parseMangaJson(res.getJSONObject(id)))
        }
        return MangasPage(mangas, false)
    }

    private fun getMangaListFromJson(json: String): List<JSONObject> {
        val arr = JSONObject(json).getJSONArray("data")
        val mangaJson = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            mangaJson.add(arr.getJSONObject(i))
        }
        return mangaJson
    }

    private fun getMangasPageFromJsonList(arr: List<JSONObject>): MangasPage {
        val mangas = ArrayList<SManga>(arr.size)
        for (obj in arr) {
            mangas.add(parseMangaJson(obj))
        }
        return MangasPage(mangas, false)
    }

    private fun parseMangaJson(obj: JSONObject): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain("/${obj.getString("oid")}")
            title = obj.getString("name")
            thumbnail_url = obj.getString("thumbnailURL")
        }
    }

    private fun sortByRank(arr: List<JSONObject>): List<JSONObject> {
        return arr.sortedBy { it.getInt("rank") }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    mangaDetailsParse(response).apply { initialized = true }
                }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")
        val oid = manga.url.substringAfter("/")
        val jsonString = """{"fields": ["oid", "name", "titleAuthors", "releaseStatus", "description", "keyGenreList"], "oids": ["$oid"], "includes": {"keyGenreList": {"fields": ["name"]}, "titleAuthors": {"fields": ["name"], "includeKey": "author"}}}"""
        val body = RequestBody.create(jsonType, jsonString)

        return POST(apiUrl, headers, body)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val oid = manga.url.substringAfterLast("/")
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")
        val jsonString = """{"fields":["chapterList"],"oids":["$oid"],"includes":{"chapterList":{"fields":["oid","name","firstPublishedDate"]}}}"""
        val body = RequestBody.create(jsonType, jsonString)

        return POST(apiUrl, headers, body)
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val obj = JSONObject(response.body()!!.string()).getJSONObject("data")

        val mangaDetails = obj.getJSONObject(obj.keys().asSequence().toList()[obj.keys().asSequence().toList().size - 1])

        title = mangaDetails.getString("name")
        description = mangaDetails.getString("description")

        if (mangaDetails.isNull("titleAuthors")) {
            artist = ""
            author = ""
        } else {
            val people = mangaDetails.getJSONArray("titleAuthors")
            val authors = ArrayList<String>()
            val artists = ArrayList<String>()
            for (i in 0 until people.length()) {
                val person = people.getJSONObject(i)
                val name = obj.getJSONObject(person.getString("author")).getString("name")
                when (person.getString("role")) {
                    "art" -> artists.add(name)
                    "story" -> authors.add(name)
                    "story-art" -> {
                        authors.add(name)
                        artists.add(name)
                    }
                }
            }
            artist = artists.sorted().joinToString(", ")
            author = authors.sorted().joinToString(", ")
        }

        val categories = mangaDetails.getJSONArray("keyGenreList")
        val genres = ArrayList<String>(categories.length())
        for (i in 0 until categories.length()) {
            genres.add(obj.getJSONObject(categories.getString(i)).getString("name"))
        }
        genre = genres.sorted().joinToString(", ")

        status = if (mangaDetails.getString("releaseStatus") == "completed") SManga.COMPLETED else SManga.ONGOING
    }

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }

    private fun getDate(date: String): Long {
        return dateFormat.parse(date.replace("T", " ").replace("Z", "")).time ?: 0
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body()!!.string()

        if (body == "Manga is licensed") {
            throw Exception("Manga has been removed from INKR, please migrate to another source")
        }

        val obj = JSONObject(body).getJSONObject("data")
        val oid = obj.keys().asSequence().toList()[obj.keys().asSequence().toList().size - 1]
        val chapters = ArrayList<SChapter>()
        val arr = obj.getJSONObject(oid).getJSONArray("chapterList")


        // Iterate backwards to match website's sorting
        for (i in arr.length() - 1 downTo 0) {
            val chapter = obj.getJSONObject(arr.getString(i))
            chapters.add(SChapter.create().apply {
                name = chapter.getString("name")
                date_upload = getDate(chapter.getString("firstPublishedDate"))
                url = "/${chapter.getString("oid")}"
            })
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val jsonType = MediaType.parse("application/jsonType; charset=utf-8")
        val jsonString = """{"fields":["chapterPages","toTrackChapterView"],"oids":["${chapter.url.removePrefix("/")}"],"includes":{"chapterPages":{"fields":["width","height","type","safeArea","pageAvgColor","pageTextColor"],"includeKey":"page"}}}"""
        val body = RequestBody.create(jsonType, jsonString)

        val headersModified = headersBuilder()
            .add("cf-ipcountry", Locale.getDefault().toString().replace("_", "-"))
            .build()

        return POST(apiUrl, headersModified, body)
    }

    override fun pageListParse(response: Response): List<Page> {
        val respJson = JSONObject(response.body()!!.string()).getJSONObject("data")
        val obj = respJson.getJSONObject(respJson.keys().asSequence().toList().getString(0)).getJSONArray("chapterPages")
        val pages = ArrayList<Page>()
        for (i in 0 until obj.length()) {
            pages.add(Page(i, "", obj.getJSONObject(i).getString("url") + "/w1600.ikc"))
        }
        return pages
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("This method should not be called!")

    // See drawWebpToCanvas function in the site's client.js file
    // Extracted code: https://jsfiddle.net/6h2sLcs4/30/
    private fun decodeMri(response: Response): ByteArray {
        val data = response.body()!!.bytes()

        // Decode file if it starts with "E" (space when XOR-ed later)
        if (data[0] != 69.toByte()) return data

        // Reconstruct WEBP header
        // Doc: https://developers.google.com/speed/webp/docs/riff_container#webp_file_header
        val buffer = ByteArray(data.size + 15)
        val size = data.size + 7
        buffer[0] = 82 // R
        buffer[1] = 73 // I
        buffer[2] = 70 // F
        buffer[3] = 70 // F
        buffer[4] = (255.toByte() and size.toByte())
        buffer[5] = (size ushr 8).toByte() and 255.toByte()
        buffer[6] = (size ushr 16).toByte() and 255.toByte()
        buffer[7] = (size ushr 24).toByte() and 255.toByte()
        buffer[8] = 87 // W
        buffer[9] = 69 // E
        buffer[10] = 66 // B
        buffer[11] = 80 // P
        buffer[12] = 86 // V
        buffer[13] = 80 // P
        buffer[14] = 56 // 8

        // Decrypt file content using XOR cipher with 101 as the key
        val cipherKey = 101.toByte()
        for (r in data.indices) {
            buffer[r + 15] = cipherKey xor data[r]
        }

        return buffer
    }

    private class StatusFilter : Filter.TriState("Completed")

    private class SortBy : UriPartFilter("Sort by", arrayOf(
            Pair("Name", "name"),
            Pair("Rank", "rank")
    ))

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    override fun getFilterList() = FilterList(
            // Search and filter don't work at the same time
            Filter.Header("NOTE: Ignored if using text search!"),
            Filter.Separator(),
            StatusFilter(),
            SortBy(),
            GenreList(getGenreList())
    )

    // [...document.querySelectorAll('._2DMqI .mdl-checkbox')].map(n => `Genre("${n.querySelector('.mdl-checkbox__label').innerText}", "${n.querySelector('input').dataset.oid}")`).sort().join(',\n')
    // on https://mangarock.com/manga
    private fun getGenreList() = listOf(
            Genre("Action", "mrs-genre-304068"),
            Genre("Adventure", "mrs-genre-304087"),
            Genre("Comedy", "mrs-genre-304069"),
            Genre("Drama", "mrs-genre-304177"),
            Genre("Ecchi", "mrs-genre-304074"),
            Genre("Fantasy", "mrs-genre-304089"),
            Genre("Historical", "mrs-genre-304306"),
            Genre("Horror", "mrs-genre-304259"),
            Genre("Magic", "mrs-genre-304090"),
            Genre("Martial Arts", "mrs-genre-304072"),
            Genre("Mecha", "mrs-genre-304245"),
            Genre("Military", "mrs-genre-304091"),
            Genre("Music", "mrs-genre-304589"),
            Genre("Psychological", "mrs-genre-304176"),
            Genre("Romance", "mrs-genre-304073"),
            Genre("School Life", "mrs-genre-304076"),
            Genre("Shounen Ai", "mrs-genre-304307"),
            Genre("Slice of Life", "mrs-genre-304195"),
            Genre("Sports", "mrs-genre-304367"),
            Genre("Supernatural", "mrs-genre-304067"),
            Genre("Vampire", "mrs-genre-304765")
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
            Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
