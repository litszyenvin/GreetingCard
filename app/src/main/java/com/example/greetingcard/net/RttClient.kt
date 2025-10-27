package com.example.greetingcard.net

import com.example.greetingcard.BuildConfig
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.UnknownHostException

object RttClient {
    private const val BASE = "https://api.rtt.io/api/v1/json/"

    private val lock = Any()

    @Volatile
    private var client: OkHttpClient = newClient()

    private val dohDns: DnsOverHttps by lazy { buildDohDns() }

    fun invalidate() {
        synchronized(lock) {
            client.connectionPool.evictAll()
            client = newClient()
        }
    }

    private fun newClient(): OkHttpClient {
        val creds = Credentials.basic(BuildConfig.RTT_USER, BuildConfig.RTT_PASS)
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(resilientDns())
            .addInterceptor { chain ->
                val req: Request = chain.request().newBuilder()
                    .header("Authorization", creds)
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private fun resilientDns(): Dns {
        val system = Dns.SYSTEM
        return Dns { hostname ->
            try {
                system.lookup(hostname)
            } catch (e: UnknownHostException) {
                try {
                    dohDns.lookup(hostname)
                } catch (fallback: Exception) {
                    throw e
                }
            }
        }
    }

    private fun buildDohDns(): DnsOverHttps {
        val bootstrap = listOf("1.1.1.1", "1.0.0.1").map { InetAddress.getByName(it) }
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        return DnsOverHttps.Builder()
            .client(client)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(bootstrap)
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
        return execute(request)
    }

    private fun execute(request: Request, attempt: Int = 0): JSONObject {
        val activeClient = client
        return try {
            activeClient.newCall(request).execute().use { resp: Response ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                JSONObject(body)
            }
        } catch (e: UnknownHostException) {
            if (attempt == 0) {
                invalidate()
                execute(request, attempt + 1)
            } else {
                throw e
            }
        }
    }
}

/** Small JSON helpers */
fun JSONObject.optArray(name: String): JSONArray =
    if (has(name) && !isNull(name)) getJSONArray(name) else JSONArray()
fun JSONObject.optObj(name: String): JSONObject? =
    if (has(name) && !isNull(name)) getJSONObject(name) else null
