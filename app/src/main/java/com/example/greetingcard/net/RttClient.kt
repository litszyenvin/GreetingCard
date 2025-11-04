package com.example.greetingcard.net

import com.example.greetingcard.BuildConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object RttClient {
    private val baseUrl: HttpUrl = "https://api.rtt.io/api/v1/json/".toHttpUrl()

    private val creds: String by lazy { Credentials.basic(BuildConfig.RTT_USER, BuildConfig.RTT_PASS) }

    private fun baseClientBuilder(): OkHttpClient.Builder =
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

    private val primaryClient: OkHttpClient by lazy { baseClientBuilder().build() }

    private val dohClient: OkHttpClient by lazy {
        val dns = DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001")
            )
            .build()

        baseClientBuilder().dns(dns).build()
    }

    /** Format now as yyyy/MM/dd/HHmm */
    private fun nowYmdHm(): String =
        SimpleDateFormat("yyyy/MM/dd/HHmm", Locale.UK).format(Date())

    fun search(origin: String, dest: String): JSONObject {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addPathSegment(origin)
            .addPathSegment("to")
            .addPathSegment(dest)
            .addPathSegment(nowYmdHm())
            .build()
        return getJson(url)
    }

    fun service(uid: String, runDate: String): JSONObject {
        // runDate: "YYYY-MM-DD"
        val y = runDate.substring(0, 4)
        val m = runDate.substring(5, 7)
        val d = runDate.substring(8, 10)
        val url = baseUrl.newBuilder()
            .addPathSegment("service")
            .addPathSegment(uid)
            .addPathSegment(y)
            .addPathSegment(m)
            .addPathSegment(d)
            .build()
        return getJson(url)
    }

    private fun getJson(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url).get().build()
        var lastError: Throwable? = null

        repeat(REQUEST_ATTEMPTS) { attempt ->
            try {
                executeWithFallback(request).use { resp: Response ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    return JSONObject(body)
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt < REQUEST_ATTEMPTS - 1) {
                    if (error is java.io.IOException) {
                        primaryClient.connectionPool.evictAll()
                    }
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        throw lastError ?: IllegalStateException("Unknown error fetching ${url.encodedPath}")
    }

    private fun executeWithFallback(request: Request): Response =
        runCatching { primaryClient.newCall(request).execute() }
            .recoverCatching { error ->
                if (isDnsFailure(error)) {
                    dohClient.newCall(request).execute()
                } else {
                    throw error
                }
            }
            .getOrElse { throw it }

    private tailrec fun isDnsFailure(error: Throwable?): Boolean = when (error) {
        null -> false
        is UnknownHostException -> true
        else -> isDnsFailure(error.cause)
    }

    private const val REQUEST_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 250L
}

/** Small JSON helpers */
fun JSONObject.optArray(name: String): JSONArray =
    if (has(name) && !isNull(name)) getJSONArray(name) else JSONArray()
fun JSONObject.optObj(name: String): JSONObject? =
    if (has(name) && !isNull(name)) getJSONObject(name) else null
