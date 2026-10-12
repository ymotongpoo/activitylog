package net.ymotongpoo.activitylog.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.collect.AppCategories
import net.ymotongpoo.activitylog.settings.CategorySettings
import net.ymotongpoo.activitylog.settings.CategoryStore
import java.util.concurrent.Executors

/** Assign categories to installed apps, with recently used apps first. */
class CategoriesActivity : Activity() {
    private data class AppRow(val packageName: String, val label: String, val androidCategory: String, val lastUsed: Long)

    private lateinit var store: CategoryStore
    private lateinit var rules: CategorySettings
    private lateinit var search: EditText
    private lateinit var unclassified: CheckBox
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: ArrayAdapter<AppRow>
    private var apps: List<AppRow> = emptyList()
    private val loader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)
        store = CategoryStore(this)
        rules = store.load()
        val root = findViewById<View>(R.id.categories_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val padding = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
            WindowInsetsCompat.CONSUMED
        }
        search = findViewById(R.id.category_search)
        unclassified = findViewById(R.id.category_unclassified)
        count = findViewById(R.id.category_count)
        empty = findViewById(R.id.category_empty)
        val list = findViewById<ListView>(R.id.category_apps)
        adapter = object : ArrayAdapter<AppRow>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val app = getItem(position)!!
                view.findViewById<TextView>(android.R.id.text1).text = app.label
                val category = rules.resolve(app.packageName, app.androidCategory)
                val source = if (app.packageName in rules.assignments) R.string.category_manual else R.string.category_android
                view.findViewById<TextView>(android.R.id.text2).apply {
                    text = getString(R.string.category_app_detail, category, getString(source), app.packageName)
                    maxLines = 3
                }
                view.minimumHeight = (80 * resources.displayMetrics.density).toInt()
                return view
            }
        }
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ -> chooseCategory(adapter.getItem(position)!!) }
        search.doAfterTextChanged { refreshApps() }
        unclassified.setOnCheckedChangeListener { _, _ -> refreshApps() }
        findViewById<Button>(R.id.categories_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.categories_manage).setOnClickListener { manageCategories() }
        findViewById<Button>(R.id.categories_add).setOnClickListener { editCategory() }
        empty.setText(R.string.category_loading)
        loadApps()
    }

    private fun loadApps() {
        // Package labels and usage history can be slow; keep them off the UI thread.
        val assignedPackages = rules.assignments.keys.toSet()
        loader.execute {
            val result = runCatching {
                val now = System.currentTimeMillis()
                val recent = runCatching {
                    getSystemService(UsageStatsManager::class.java)
                        ?.queryAndAggregateUsageStats(now - 7L * 24 * 60 * 60 * 1_000, now).orEmpty()
                }.getOrDefault(emptyMap())
                val installed = packageManager.getInstalledApplications(0).map { ai ->
                    val label = runCatching { packageManager.getApplicationLabel(ai).toString() }
                        .getOrDefault(ai.packageName).ifBlank { ai.packageName }
                    AppRow(ai.packageName, label, AppCategories.fromAndroidCategory(ai.category), recent[ai.packageName]?.lastTimeUsed ?: 0)
                }
                val missing = assignedPackages - installed.map { it.packageName }.toSet()
                (installed + missing.map { AppRow(it, it, AppCategories.UNCATEGORIZED, 0) })
                    .sortedWith(compareByDescending<AppRow> { it.lastUsed }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    apps = it
                    refreshApps()
                }.onFailure {
                    empty.setText(R.string.category_load_failed)
                }
            }
        }
    }

    private fun refreshApps() {
        val query = search.text.toString().trim()
        val matching = apps.filter {
            (!unclassified.isChecked || rules.resolve(it.packageName, it.androidCategory) == AppCategories.UNCATEGORIZED) &&
                (it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true))
        }
        adapter.clear()
        adapter.addAll(matching)
        count.text = getString(R.string.category_app_count, matching.size)
        empty.setText(R.string.category_no_apps)
    }

    private fun save(updated: CategorySettings) {
        store.save(updated)
        rules = updated
        refreshApps()
    }

    private fun chooseCategory(app: AppRow) {
        val categories = rules.categories.toList()
        val options = listOf(getString(R.string.category_automatic, app.androidCategory)) + categories
        val selected = categories.indexOf(rules.assignments[app.packageName]) + 1
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                save(rules.assign(app.packageName, if (which == 0) null else categories[which - 1]))
                dialog.dismiss()
            }
            .setNeutralButton(R.string.category_new) { _, _ -> editCategory(assignTo = app.packageName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun manageCategories() {
        if (rules.categories.isEmpty()) {
            editCategory()
            return
        }
        val categories = rules.categories.toList()
        val labels = categories.map { name ->
            getString(R.string.category_with_count, name, rules.assignments.count { it.value == name })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.categories_manage)
            .setItems(labels.toTypedArray()) { _, which -> categoryActions(categories[which]) }
            .setPositiveButton(R.string.category_new) { _, _ -> editCategory() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun categoryActions(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.category_rename), getString(R.string.category_delete))) { _, which ->
                if (which == 0) editCategory(oldName = name) else confirmDelete(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editCategory(oldName: String? = null, assignTo: String? = null) {
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(CategorySettings.MAX_NAME_LENGTH))
            hint = getString(R.string.category_name_hint)
            setText(oldName.orEmpty())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (oldName == null) R.string.category_new else R.string.category_rename)
            .setView(input)
            .setPositiveButton(R.string.button_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val name = input.text.toString().trim()
                    var updated = if (oldName == null) rules.add(name) else rules.rename(oldName, name)
                    if (assignTo != null) updated = updated.assign(assignTo, name)
                    save(updated)
                    dialog.dismiss()
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                } catch (e: IllegalArgumentException) {
                    input.error = e.message
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.category_delete_title, name))
            .setMessage(R.string.category_delete_message)
            .setPositiveButton(R.string.category_delete) { _, _ -> save(rules.remove(name)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }
}
