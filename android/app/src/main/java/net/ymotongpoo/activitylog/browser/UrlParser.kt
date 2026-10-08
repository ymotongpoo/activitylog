package net.ymotongpoo.activitylog.browser

import java.util.Locale

/** URL components as reported in `url.*` attributes. */
data class ParsedUrl(
    val scheme: String,
    /** Lowercase host without port. */
    val domain: String,
    /** Port including the leading colon, or empty. */
    val port: String,
    /** Path, at least "/". */
    val path: String,
    /** Query without '?', or null. */
    val query: String?,
    /** Fragment without '#', or null. */
    val fragment: String?,
) {
    val origin: String get() = "$scheme://$domain$port"
    val withoutQuery: String get() = origin + path
    val full: String
        get() = buildString {
            append(withoutQuery)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
}

/**
 * Lenient parser for the text shown in a browser's address bar. Chrome usually hides
 * the scheme ("github.com/foo"), so text without a scheme is treated as https.
 * Returns null for text that does not look like a web URL (search terms, hints, ...).
 */
object UrlParser {
    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val hostRegex = Regex("^[\\p{L}\\p{N}._~%-]+$")

    fun parse(raw: String?): ParsedUrl? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val m = schemeRegex.find(text)
        val scheme = m?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "https"
        if (scheme != "http" && scheme != "https") return null
        val rest = if (m != null) text.substring(m.range.last + 1) else text

        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authorityEnd)
        var tail = rest.substring(authorityEnd)
        if (authority.any { it.isWhitespace() }) return null
        authority = authority.substringAfterLast('@')

        val host: String
        var port = ""
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1).lowercase(Locale.ROOT)
            val after = authority.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.matches(Regex(":\\d{1,5}"))) return null
                port = after
            }
        } else {
            val colon = authority.lastIndexOf(':')
            val h = if (colon >= 0) authority.substring(0, colon) else authority
            if (colon >= 0) {
                val p = authority.substring(colon + 1)
                if (!p.matches(Regex("\\d{1,5}"))) return null
                port = ":$p"
            }
            host = h.trimEnd('.').lowercase(Locale.ROOT)
            if (host.isEmpty() || !hostRegex.matches(host)) return null
            if (!host.contains('.') && host != "localhost") return null
            if (host.startsWith('.') || host.contains("..")) return null
        }
        // Default ports carry no information.
        if ((scheme == "https" && port == ":443") || (scheme == "http" && port == ":80")) port = ""

        tail = tail.replace(Regex("\\s"), "%20")
        var fragment: String? = null
        val hash = tail.indexOf('#')
        if (hash >= 0) {
            fragment = tail.substring(hash + 1)
            tail = tail.substring(0, hash)
        }
        var query: String? = null
        val q = tail.indexOf('?')
        if (q >= 0) {
            query = tail.substring(q + 1)
            tail = tail.substring(0, q)
        }
        val path = tail.ifEmpty { "/" }
        return ParsedUrl(scheme, host, port, path, query, fragment)
    }
}
