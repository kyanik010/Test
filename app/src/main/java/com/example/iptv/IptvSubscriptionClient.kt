package com.example.iptv

import android.net.Uri
import com.example.CredentialSanitizer
import com.example.NetworkSecurityValidator
import com.example.XtreamAccountValidator
import com.example.audio.AudioChannelGroup
import com.example.audio.AudioSourceClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class LoadedSubscriptionCatalog(
    val accountInfo: IptvAccountInfo?,
    val channels: List<IptvChannel>,
    val warnings: List<String>
)

/**
 * Fetches channels from a SINGLE IPTV subscription (either Xtream Codes or single M3U URL).
 */
object IptvSubscriptionClient {

    private const val USER_AGENT = "SONIX-TV/1.0"

    fun loadFromXtream(
        rawServer: String,
        rawUsername: String,
        rawPassword: String,
        formatPreference: StreamFormatPreference
    ): LoadedSubscriptionCatalog {
        val server = normalizeServerUrl(rawServer)
        NetworkSecurityValidator.assertSafeUrl(server)

        val username = rawUsername.trim()
        val password = rawPassword.trim()
        require(username.isNotBlank() && password.isNotBlank()) {
            "أدخل اسم المستخدم وكلمة المرور للاشتراك"
        }

        val warnings = mutableListOf<String>()

        val authUrl = buildPlayerApiUrl(server, username, password, null)
        val authBody = fetchText(authUrl)
        val authJson = JSONObject(authBody)
        val userInfo = XtreamAccountValidator.validate(authJson, System.currentTimeMillis())

        val maxCons = userInfo.optString("max_connections", "").trim().toIntOrNull()
        val activeCons = userInfo.optString("active_cons", "").trim().toIntOrNull()
        val allowedArray = userInfo.optJSONArray("allowed_output_formats")
        val allowedFormats = buildList {
            if (allowedArray != null) {
                for (i in 0 until allowedArray.length()) {
                    val fmt = allowedArray.optString(i, "").trim().lowercase()
                    if (fmt.isNotBlank()) add(fmt)
                }
            }
        }

        if (maxCons != null && maxCons <= 1) {
            warnings += "تحذير مزود IPTV: الاشتراك يسمح باتصال متزامن واحد فقط (max_connections=$maxCons). فتح قناتين معاً قد يرفضه الخادم (403/509) أو يقطع الفيديو."
        }

        val preferredExt = when (formatPreference) {
            StreamFormatPreference.HLS -> "m3u8"
            StreamFormatPreference.TS -> "ts"
            StreamFormatPreference.AUTO -> if (allowedFormats.contains("m3u8") || allowedFormats.isEmpty()) "m3u8" else allowedFormats.first()
        }

        val accountInfo = IptvAccountInfo(
            username = userInfo.optString("username", username),
            status = userInfo.optString("status", "Active"),
            maxConnections = maxCons,
            activeConnections = activeCons,
            allowedFormats = allowedFormats,
            expirationText = userInfo.optString("exp_date", "")
        )

        val categoryMap = mutableMapOf<String, String>()
        try {
            val catBody = fetchText(buildPlayerApiUrl(server, username, password, "get_live_categories"))
            val catArray = JSONArray(catBody)
            for (i in 0 until catArray.length()) {
                val obj = catArray.optJSONObject(i) ?: continue
                val id = obj.optString("category_id", "").trim()
                val name = obj.optString("category_name", "").trim()
                if (id.isNotBlank() && name.isNotBlank()) {
                    categoryMap[id] = name
                }
            }
        } catch (_: Exception) {
            warnings += "تعذر جلب تصنيفات البث المباشر، سيتم عرض القنوات بدون تصنيف"
        }

        val streamsBody = fetchText(buildPlayerApiUrl(server, username, password, "get_live_streams"))
        val streamsArray = JSONArray(streamsBody)
        val channels = ArrayList<IptvChannel>(streamsArray.length())

        val encUser = Uri.encode(username)
        val encPass = Uri.encode(password)

        for (i in 0 until streamsArray.length()) {
            val obj = streamsArray.optJSONObject(i) ?: continue
            val streamId = obj.optString("stream_id", "").trim()
            if (streamId.isBlank()) continue
            val name = obj.optString("name", "Channel $streamId").trim()
            val num = obj.optString("num", (i + 1).toString())
            val catId = obj.optString("category_id", "").trim()
            val catName = categoryMap[catId] ?: obj.optString("category_name", "")
            val primaryUrl = "$server/live/$encUser/$encPass/$streamId.$preferredExt"
            val tsUrl = "$server/live/$encUser/$encPass/$streamId.ts"
            val group = AudioSourceClassifier.classify(name, catName, catId)

            channels.add(
                IptvChannel(
                    id = streamId,
                    name = name,
                    number = num,
                    categoryName = catName,
                    streamUrl = primaryUrl,
                    alternateTsUrl = if (preferredExt != "ts") tsUrl else "$server/live/$encUser/$encPass/$streamId.m3u8",
                    audioGroup = group,
                    isAudioCandidate = group != AudioChannelGroup.OTHER
                )
            )
        }

        return LoadedSubscriptionCatalog(
            accountInfo = accountInfo,
            channels = channels,
            warnings = warnings
        )
    }

    fun loadFromM3uUrl(rawM3uUrl: String): LoadedSubscriptionCatalog {
        val url = rawM3uUrl.trim()
        NetworkSecurityValidator.assertSafeUrl(url)

        val warnings = mutableListOf<String>()
        val channels = parseM3uStream(url)
        if (channels.isEmpty()) {
            warnings += "لم يتم العثور على قنوات صالحة داخل رابط M3U"
        }
        return LoadedSubscriptionCatalog(
            accountInfo = null,
            channels = channels,
            warnings = warnings
        )
    }

    fun parseM3uText(m3uContent: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        val reader = BufferedReader(m3uContent.reader())
        var pendingName = ""
        var pendingGroup = ""
        var index = 1

        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    pendingGroup = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    val commaIdx = line.lastIndexOf(',')
                    pendingName = if (commaIdx >= 0 && commaIdx + 1 < line.length) {
                        line.substring(commaIdx + 1).trim()
                    } else {
                        "Channel $index"
                    }
                } else if (!line.startsWith("#") && (line.startsWith("http://", true) || line.startsWith("https://", true))) {
                    val safeName = pendingName.ifBlank { "Channel $index" }
                    val group = AudioSourceClassifier.classify(safeName, pendingGroup, null)
                    channels.add(
                        IptvChannel(
                            id = "m3u_$index",
                            name = safeName,
                            number = index.toString(),
                            categoryName = pendingGroup,
                            streamUrl = line,
                            audioGroup = group,
                            isAudioCandidate = group != AudioChannelGroup.OTHER
                        )
                    )
                    index++
                    pendingName = ""
                    pendingGroup = ""
                }
            }
        }
        return channels
    }

    private fun parseM3uStream(urlString: String): List<IptvChannel> {
        val text = fetchText(urlString, maxBytes = 16 * 1024 * 1024)
        return parseM3uText(text)
    }

    fun normalizeServerUrl(rawServer: String): String {
        val trimmed = rawServer.trim().trimEnd('/')
        require(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            "أدخل عنوان الخادم يبدأ بـ http:// أو https://"
        }
        return trimmed
    }

    private fun buildPlayerApiUrl(server: String, username: String, password: String, action: String?): String {
        val base = "$server/player_api.php?username=${Uri.encode(username)}&password=${Uri.encode(password)}"
        return if (action.isNullOrBlank()) base else "$base&action=${Uri.encode(action)}"
    }

    private fun fetchText(urlString: String, maxBytes: Int = 12 * 1024 * 1024): String {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            NetworkSecurityValidator.assertSafeUrl(currentUrl)
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val isGzip = conn.contentEncoding.equals("gzip", ignoreCase = true)
                val body = stream?.let { raw ->
                    val unwrapped = if (isGzip) GZIPInputStream(raw) else raw
                    unwrapped.use { s ->
                        val buffer = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            val read = s.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) break
                            out.write(buffer, 0, read)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }.orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException("خطأ HTTP $code عند الاتصال بـ ${CredentialSanitizer.sanitizeUrl(currentUrl)}")
                }
                require(body.isNotBlank()) { "أعاد الخادم استجابة فارغة" }
                return body
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("تم تجاوز الحد الأقصى لإعادة التوجيه")
    }
}
