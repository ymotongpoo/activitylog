package net.ymotongpoo.activitylog.collect

import net.ymotongpoo.activitylog.browser.ParsedUrl
import net.ymotongpoo.activitylog.otlp.Attribute
import net.ymotongpoo.activitylog.otlp.attr
import java.util.Locale

/** How much of a URL goes into `url.full`. */
enum class UrlMode(val id: String) {
    /** Full URL. */
    FULL("full"),

    /** Drop query and fragment. */
    PATH("path"),

    /** Scheme and host only. */
    DOMAIN("domain"),

    /** No `url.full` / `url.path` at all. */
    NONE("none");

    companion object {
        fun fromId(id: String?): UrlMode = entries.firstOrNull { it.id == id } ?: PATH
    }
}

/** Case-insensitive glob with `*` (any run of characters) and `?` (one character). */
class Glob(pattern: String) {
    private val regex: Regex = buildString {
        append('^')
        for (ch in pattern.trim()) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
        }
        append('$')
    }.toRegex(RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean = regex.matches(text)
}

class PrivacyPolicy(
    excludedPackages: List<String> = emptyList(),
    excludedDomains: List<String> = emptyList(),
    val urlMode: UrlMode = UrlMode.PATH,
) {
    private val packageGlobs = excludedPackages.map { it.trim() }.filter { it.isNotEmpty() }.map(::Glob)
    private val domainGlobs = excludedDomains.map { it.trim() }.filter { it.isNotEmpty() }.map(::Glob)

    /** Matches the package name or the app label. */
    fun isAppExcluded(packageName: String, label: String?): Boolean =
        packageGlobs.any { it.matches(packageName) || (label != null && it.matches(label)) }

    fun isDomainExcluded(domain: String): Boolean = domainGlobs.any { it.matches(domain.lowercase(Locale.ROOT)) }

    /** `url.*` attributes after applying the URL mode. */
    fun urlAttributes(url: ParsedUrl): List<Attribute> {
        val attrs = mutableListOf<Attribute>()
        when (urlMode) {
            UrlMode.FULL -> attrs += attr("url.full", url.full)
            UrlMode.PATH -> attrs += attr("url.full", url.withoutQuery)
            UrlMode.DOMAIN -> attrs += attr("url.full", url.origin)
            UrlMode.NONE -> Unit
        }
        attrs += attr("url.scheme", url.scheme)
        attrs += attr("url.domain", url.domain)
        if (urlMode == UrlMode.FULL || urlMode == UrlMode.PATH) attrs += attr("url.path", url.path)
        return attrs
    }

    companion object {
        /** Splits a settings text area into entries (newline or comma separated). */
        fun parseList(text: String?): List<String> =
            text.orEmpty().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
