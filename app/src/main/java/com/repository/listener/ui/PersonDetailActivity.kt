package com.repository.listener.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.ImageCacheUtil
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON_ID = "person_id"
        private const val TAG = "PersonDetail"
        private const val PREFS_NAME = "reid_prefs"
        private const val KEY_PINNED = "pinned_persons"
    }

    private lateinit var client: ReidAnalyticsClient
    private var personId: String = ""
    private var baseUrl: String = ""
    private var apiKey: String = ""
    private var isHidden = false
    private var isPinned = false

    private lateinit var chipHidden: TextView
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_person_detail)

        personId = intent.getStringExtra(EXTRA_PERSON_ID) ?: return finish()
        baseUrl = AppConfig.getOrchestratorHttpUrl(this)
        apiKey = AppConfig.getApiKey(this)
        client = ReidAnalyticsClient(baseUrl, apiKey)

        // Toolbar
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Header
        val imgAvatar = findViewById<ImageView>(R.id.imgPersonAvatar)
        ImageCacheUtil.loadPersonImage(imgAvatar, personId, baseUrl, apiKey)

        val textPersonId = findViewById<TextView>(R.id.textPersonId)
        textPersonId.text = personId

        chipHidden = findViewById(R.id.chipHidden)

        // Load pinned state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pinnedSet = prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()
        isPinned = pinnedSet.contains(personId)
        updatePinIcon()

        // Load person data for hidden state
        lifecycleScope.launch {
            try {
                val person = withContext(Dispatchers.IO) { client.getPerson(personId) }
                if (person != null) {
                    isHidden = person.optBoolean("is_active", true) == false
                    updateHiddenBadge()
                    updateHideIcon()
                    val displayName = person.optString("display_name", "").ifEmpty { null }
                    textPersonId.text = displayName ?: personId
                } else {
                    textPersonId.text = personId
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load person failed: ${e.message}")
            }
        }

        // Listen for intel updates from PersonIntelFragment
        supportFragmentManager.setFragmentResultListener("intel_updated", this) { _, _ ->
            refreshPersonData()
        }

        // Toolbar menu
        toolbar.setOnMenuItemClickListener { item -> onMenuAction(item) }

        // Tabs
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        val adapter = PersonDetailTabAdapter(
            supportFragmentManager, lifecycle, personId, baseUrl, apiKey
        )
        viewPager.adapter = adapter

        // "Intel" is a region-specific ReID tab, shown only when ENABLE_REID_RU_TABS is set.
        val tabTitles = if (com.repository.listener.BuildConfig.ENABLE_REID_RU_TABS) {
            arrayOf("Overview", "Map", "Similar", "Intel")
        } else {
            arrayOf("Overview", "Map", "Similar")
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    private fun onMenuAction(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_hide -> {
                toggleHide()
                true
            }
            R.id.action_delete -> {
                showDeleteDialog()
                true
            }
            R.id.action_pin -> {
                togglePin()
                true
            }
            else -> false
        }
    }

    private fun toggleHide() {
        val newActive = isHidden // if hidden, make active (true); if active, make hidden (false)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.updatePerson(personId, newActive)
                }
                if (result != null) {
                    isHidden = !newActive
                    updateHiddenBadge()
                    updateHideIcon()
                    Toast.makeText(
                        this@PersonDetailActivity,
                        if (isHidden) "Person hidden" else "Person unhidden",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Toggle hide failed: ${e.message}")
            }
        }
    }

    private fun showDeleteDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Person")
            .setMessage("Are you sure? This will permanently delete this person and all their sightings and traits.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            client.deletePerson(personId)
                        }
                        if (success) {
                            Toast.makeText(this@PersonDetailActivity, "Person deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this@PersonDetailActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Delete failed: ${e.message}")
                    }
                }
            }
            .show()
    }

    private fun togglePin() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_PINNED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isPinned) {
            current.remove(personId)
        } else {
            current.add(personId)
        }
        prefs.edit().putStringSet(KEY_PINNED, current).apply()
        isPinned = !isPinned
        updatePinIcon()
        Toast.makeText(this, if (isPinned) "Person pinned" else "Person unpinned", Toast.LENGTH_SHORT).show()
    }

    private fun updateHiddenBadge() {
        chipHidden.visibility = if (isHidden) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateHideIcon() {
        val menuItem = toolbar.menu.findItem(R.id.action_hide) ?: return
        menuItem.setIcon(if (isHidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        menuItem.title = if (isHidden) "Unhide" else "Hide"
    }

    private fun updatePinIcon() {
        val menuItem = toolbar.menu.findItem(R.id.action_pin) ?: return
        menuItem.setIcon(if (isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
        menuItem.title = if (isPinned) "Unpin" else "Pin"
    }

    private fun refreshPersonData() {
        lifecycleScope.launch {
            try {
                val person = withContext(Dispatchers.IO) { client.getPerson(personId) }
                if (person != null) {
                    val displayName = person.optString("display_name", "").ifEmpty { null }
                    val textPersonId = findViewById<TextView>(R.id.textPersonId)
                    textPersonId.text = displayName ?: personId
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Refresh person data failed: ${e.message}")
            }
        }
    }
}
