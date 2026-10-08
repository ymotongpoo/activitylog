package net.ymotongpoo.activitylog.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlParserTest {
    @Test
    fun addsSchemeWhenMissing() {
        val u = UrlParser.parse("github.com/ymotongpoo/activitylog")!!
        assertEquals("https", u.scheme)
        assertEquals("github.com", u.domain)
        assertEquals("/ymotongpoo/activitylog", u.path)
        assertEquals("https://github.com/ymotongpoo/activitylog", u.full)
    }

    @Test
    fun parsesComponents() {
        val u = UrlParser.parse("  HTTP://user@Example.COM:8080/a b?x=1#frag ")!!
        assertEquals("http", u.scheme)
        assertEquals("example.com", u.domain)
        assertEquals(":8080", u.port)
        assertEquals("/a%20b", u.path)
        assertEquals("x=1", u.query)
        assertEquals("frag", u.fragment)
        assertEquals("http://example.com:8080/a%20b", u.withoutQuery)
    }

    @Test
    fun domainOnly() {
        val u = UrlParser.parse("www.example.co.jp")!!
        assertEquals("/", u.path)
        assertEquals("https://www.example.co.jp/", u.full)
        assertEquals("https://example.com/", UrlParser.parse("example.com:443")!!.full)
        assertEquals("localhost", UrlParser.parse("http://localhost:3000/x")!!.domain)
        assertEquals("[::1]", UrlParser.parse("http://[::1]:8080/")!!.domain)
        assertEquals("日本語.jp", UrlParser.parse("日本語.jp/パス")!!.domain)
    }

    @Test
    fun rejectsNonUrls() {
        assertNull(UrlParser.parse(null))
        assertNull(UrlParser.parse(""))
        assertNull(UrlParser.parse("Search or type URL"))
        assertNull(UrlParser.parse("hello"))
        assertNull(UrlParser.parse("chrome://newtab"))
        assertNull(UrlParser.parse("file:///sdcard/a.html"))
        assertNull(UrlParser.parse("example.com:abc"))
        assertNull(UrlParser.parse("a..b"))
    }
}
