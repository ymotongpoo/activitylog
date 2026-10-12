package net.ymotongpoo.activitylog.settings

import android.content.Context
import androidx.core.content.edit
import net.ymotongpoo.activitylog.collect.AppCategories
import org.json.JSONArray
import org.json.JSONObject

/** Category names and package assignments are saved together, including on rename/delete. */
data class CategorySettings(
    val categories: List<String> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
) {
    fun resolve(packageName: String, androidCategory: String): String =
        assignments[packageName]?.takeIf { it in categories } ?: androidCategory

    fun add(name: String): CategorySettings {
        val normalized = checkedName(name)
        require(normalized !in categories) { "Category already exists" }
        return copy(categories = (categories + normalized).sorted())
    }

    fun rename(oldName: String, newName: String): CategorySettings {
        require(oldName in categories)
        val normalized = checkedName(newName)
        require(normalized == oldName || normalized !in categories) { "Category already exists" }
        return copy(
            categories = categories.map { if (it == oldName) normalized else it }.sorted(),
            assignments = assignments.mapValues { if (it.value == oldName) normalized else it.value },
        )
    }

    fun remove(name: String): CategorySettings = copy(
        categories = categories - name,
        assignments = assignments.filterValues { it != name },
    )

    /** Null restores the Android category for this app. */
    fun assign(packageName: String, category: String?): CategorySettings {
        require(packageName.isNotBlank())
        require(category == null || category in categories)
        return copy(assignments = if (category == null) assignments - packageName else assignments + (packageName to category))
    }

    fun toJson(): String = JSONObject()
        .put("categories", JSONArray(categories))
        .put("assignments", JSONObject(assignments))
        .toString()

    companion object {
        const val MAX_NAME_LENGTH = 80

        private fun checkedName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl)) {
                "Use 1–80 characters on one line"
            }
            require(it != AppCategories.UNCATEGORIZED) { "Uncategorized is reserved; choose a category name" }
        }

        fun fromJson(text: String): CategorySettings {
            val o = JSONObject(text)
            val names = o.getJSONArray("categories")
            val categories = (0 until names.length()).map { checkedName(names.getString(it)) }.distinct().sorted()
            val raw = o.getJSONObject("assignments")
            val assignments = raw.keys().asSequence().associateWith { raw.getString(it) }
                .filter { (pkg, name) -> pkg.isNotBlank() && name in categories }
            return CategorySettings(categories, assignments)
        }
    }
}

/** Separate preferences prevent the main settings screen from overwriting category edits. */
class CategoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("categories", Context.MODE_PRIVATE)

    fun load(): CategorySettings = prefs.getString("rules", null)?.let(CategorySettings::fromJson) ?: CategorySettings()

    fun save(settings: CategorySettings) {
        prefs.edit { putString("rules", settings.toJson()) }
    }
}
