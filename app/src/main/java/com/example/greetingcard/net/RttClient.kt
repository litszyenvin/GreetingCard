package com.example.greetingcard.net

import com.example.greetingcard.BuildConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object RttClient {
    private const val BASE = "https://api.rtt.io/api/v1/json/"

    private val client: OkHttpClient by lazy {
        val creds = Credentials.basic(BuildConfig.RTT_USER, BuildConfig.RTT_PASS)
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req: Request = chain.request().newBuilder()
                    .header("Authorization", creds)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    /** Format now as yyyy/MM/dd/HHmm */
    private fun nowYmdHm(): String =
        SimpleDateFormat("yyyy/MM/dd/HHmm", Locale.UK).format(Date())

    fun search(origin: String, dest: String): JSONObject {
        val path = "search/$origin/to/$dest/${nowYmdHm()}"
        return getJson(BASE + path)
    }

    fun service(uid: String, runDate: String): JSONObject {
        // runDate: "YYYY-MM-DD"
        val y = runDate.substring(0, 4)
        val m = runDate.substring(5, 7)
        val d = runDate.substring(8, 10)
        val path = "service/$uid/$y/$m/$d"
        return getJson(BASE + path)
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp: Response ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return JSONObject(body)
        }
    }
}

/** Small JSON helpers */
fun JSONObject.optArray(name: String): JSONArray =
    if (has(name) && !isNull(name)) getJSONArray(name) else JSONArray()
fun JSONObject.optObj(name: String): JSONObject? =
    if (has(name) && !isNull(name)) getJSONObject(name) else null
