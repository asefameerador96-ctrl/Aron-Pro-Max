package com.aktcl.aron.core.media

import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Test

/** The blob client must stay cookie-less: the PublicSuffixDatabase.list asset is not in the APK (see core-network NoCookieJarTest). */
class NoCookieJarTest {
    @Test
    fun blobClientHasNoCookieJar() {
        val client = AronApiClient(ApiOrigin.parse("https://api.aron-dev.invalid"), AronApiClient.defaultOkHttp(), ClientIdentity("1.0+1") { null })
        val auth = object : MediaUploadAuth {
            override suspend fun token(): String? = null
            override suspend fun refresh(rejected: String?): Boolean = false
        }
        val api = MediaHttpApi(client, AronApiClient.defaultOkHttp(), auth)
        val f = MediaHttpApi::class.java.getDeclaredField("blobClient").apply { isAccessible = true }
        assertSame(CookieJar.NO_COOKIES, (f.get(api) as OkHttpClient).cookieJar)
    }
}
