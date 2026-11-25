package com.aktcl.aron.core.network

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The phone apps drop OkHttp's PublicSuffixDatabase.list asset from the APK (docs/status/apk-size-2026-11-18.md). The
 * list is read only by HttpUrl.topPrivateDomain() and by Cookie.parse, which runs only when a CookieJar other than
 * NO_COOKIES is installed. This test keeps the second precondition true for the shared client and its copies; the
 * repository gate tools/ci/okhttp-psl-scan.py bans `.cookieJar(`, JavaNetCookieJar and topPrivateDomain in main sources.
 */
class NoCookieJarTest {
    @Test
    fun sharedClientHasNoCookieJar() {
        assertSame(CookieJar.NO_COOKIES, AronApiClient.defaultOkHttp().cookieJar)
        assertSame(CookieJar.NO_COOKIES, AronApiClient.defaultOkHttp().newBuilder().build().cookieJar)
    }

    @Test
    fun apiClientCopyHasNoCookieJar() {
        val api = AronApiClient(
            ApiOrigin.parse("https://api.aron-dev.invalid"), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { null },
        )
        val f = AronApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }
        assertSame(CookieJar.NO_COOKIES, (f.get(api) as OkHttpClient).cookieJar)
    }
}
