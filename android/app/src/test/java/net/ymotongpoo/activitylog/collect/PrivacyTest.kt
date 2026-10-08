package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.browser.UrlParser
import net.ymotongpoo.activitylog.otlp.attr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    @Test
    fun globs() {
        assertTrue(Glob("*.example.com").matches("a.b.example.com"))
        assertFalse(Glob("*.example.com").matches("example.com"))
        assertTrue(Glob("com.bank.?pp").matches("com.bank.app"))
        assertTrue(Glob("1Password*").matches("1password 8"))
        assertFalse(Glob("a.b").matches("aXb"))
    }

    @Test
    fun appExclusionMatchesPackageOrLabel() {
        val p = PrivacyPolicy(excludedPackages = PrivacyPolicy.parseList("com.secret.*\n Bank App ,"))
        assertTrue(p.isAppExcluded("com.secret.vault", "Vault"))
        assertTrue(p.isAppExcluded("jp.bank", "Bank App"))
        assertFalse(p.isAppExcluded("com.example", "Example"))
    }

    @Test
    fun urlModes() {
        val url = UrlParser.parse("https://example.com/a/b?q=1#f")!!
        assertEquals(
            listOf(
                attr("url.full", "https://example.com/a/b?q=1#f"),
                attr("url.scheme", "https"),
                attr("url.domain", "example.com"),
                attr("url.path", "/a/b"),
            ),
            PrivacyPolicy(urlMode = UrlMode.FULL).urlAttributes(url),
        )
        assertEquals(
            attr("url.full", "https://example.com/a/b"),
            PrivacyPolicy(urlMode = UrlMode.PATH).urlAttributes(url).first(),
        )
        assertEquals(
            listOf(attr("url.full", "https://example.com"), attr("url.scheme", "https"), attr("url.domain", "example.com")),
            PrivacyPolicy(urlMode = UrlMode.DOMAIN).urlAttributes(url),
        )
        assertEquals(
            listOf(attr("url.scheme", "https"), attr("url.domain", "example.com")),
            PrivacyPolicy(urlMode = UrlMode.NONE).urlAttributes(url),
        )
        assertEquals(UrlMode.PATH, UrlMode.fromId("bogus"))
    }
}
