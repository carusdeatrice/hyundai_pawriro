package kr.routerescue.demo

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI

internal class GuardianHttpError(val status: Int) : IllegalStateException("서버 요청 실패 ($status)")

internal class GuardianClient(address: String) {
    private val base = URI(address.trim().trimEnd('/')).also {
        require(it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
        require(it.scheme == "https" || it.scheme == "http" && it.host in setOf("10.0.2.2","127.0.0.1","localhost"))
    }.toString()
    var token: String? = null

    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): Any =
        withContext(Dispatchers.IO) {
            val conn = URI(base+path).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 4000
                conn.readTimeout = if (path == "/vehicle/detour") 16000 else 6000
                conn.instanceFollowRedirects = false
                if (path.startsWith("/guardian/")) token?.let { conn.setRequestProperty("Authorization","Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type","application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw GuardianHttpError(code)
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { JSONTokener(it.readText()).nextValue() }
            } finally { conn.disconnect() }
        }
}

// The Seongsu demo map has a fixed viewport. Store geographic coordinates at the API boundary.
internal fun geoPoint(point: Offset) = JSONArray().put(127.022+point.x*.000026).put(37.557-point.y*.000025)
internal fun mapPoint(point: JSONArray) = Offset(((point.getDouble(0)-127.022)/.000026).toFloat(),
    ((37.557-point.getDouble(1))/.000025).toFloat())

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
