package com.repository.listener.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PeopleCatalogueFragment : Fragment() {

    companion object {
        private const val TAG = "PeopleCatalogue"
        private const val PAGE_SIZE = 20
        private const val POLL_INTERVAL_MS = 15_000L
        private const val SEARCH_DEBOUNCE_MS = 400L
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressLoading: ProgressBar
    private lateinit var textEmpty: TextView
    private lateinit var textPersonCount: TextView
    private lateinit var editSearch: EditText
    private lateinit var btnRefresh: MaterialButton
    private lateinit var adapter: PersonCardAdapter
    private lateinit var client: ReidAnalyticsClient

    private var allPersons: MutableList<PersonItem> = mutableListOf()
    private var totalCount: Int = 0
    private var isLoading = false
    private var hasMore = true
    private var pollJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (currentQuery.isEmpty()) {
                refreshPersons()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private var currentQuery = ""
    private var searchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_people_catalogue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerPersons)
        progressLoading = view.findViewById(R.id.progressLoading)
        textEmpty = view.findViewById(R.id.textEmpty)
        textPersonCount = view.findViewById(R.id.textPersonCount)
        editSearch = view.findViewById(R.id.editSearch)
        btnRefresh = view.findViewById(R.id.btnRefresh)

        val ctx = requireContext()
        val baseUrl = AppConfig.getOrchestratorHttpUrl(ctx)
        val apiKey = AppConfig.getApiKey(ctx)

        client = ReidAnalyticsClient(baseUrl, apiKey)

        adapter = PersonCardAdapter(baseUrl, apiKey) { person ->
            val intent = Intent(requireContext(), PersonDetailActivity::class.java).apply {
                putExtra(PersonDetailActivity.EXTRA_PERSON_ID, person.id)
            }
            startActivity(intent)
        }

        recyclerView.layoutManager = GridLayoutManager(ctx, 2)
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || currentQuery.isNotEmpty()) return
                val lm = rv.layoutManager as GridLayoutManager
                val totalItems = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (!isLoading && hasMore && lastVisible >= totalItems - 6) {
                    loadPersons(allPersons.size)
                }
            }
        })

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchRunnable?.let { handler.removeCallbacks(it) }
                if (query.isEmpty()) {
                    currentQuery = ""
                    forceRefreshPersons()
                } else {
                    searchRunnable = Runnable { performSearch(query) }
                    handler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
                }
            }
        })

        btnRefresh.setOnClickListener {
            editSearch.text.clear()
            currentQuery = ""
            forceRefreshPersons()
        }

        loadPersons(0)
    }

    override fun onResume() {
        super.onResume()
        refreshPersons()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        searchRunnable?.let { handler.removeCallbacks(it) }
        super.onPause()
    }

    private fun refreshPersons() {
        if (isLoading || context == null) return
        isLoading = true
        val fetchLimit = maxOf(allPersons.size, PAGE_SIZE)

        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.getPersons(fetchLimit, 0)
                }
                if (result != null) {
                    val items = parsePersons(result)
                    allPersons.clear()
                    allPersons.addAll(items)
                    hasMore = items.size >= fetchLimit
                    totalCount = allPersons.size
                    textPersonCount.text = "$totalCount persons"
                    adapter.submitList(allPersons.toList())
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Poll refresh failed: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    private fun forceRefreshPersons() {
        pollJob?.cancel()
        pollJob = null
        isLoading = false
        allPersons.clear()
        hasMore = true
        totalCount = 0
        adapter.submitList(emptyList())
        loadPersons(0)
    }

    private fun performSearch(query: String) {
        if (context == null) return
        currentQuery = query
        isLoading = true
        progressLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.searchPersons(query)
                }

                if (result != null) {
                    val items = parsePersons(result)
                    adapter.submitList(items)
                    textPersonCount.text = "${items.size} results"
                    textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    adapter.submitList(emptyList())
                    textEmpty.text = "Search failed"
                    textEmpty.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Search failed: ${e.message}")
            } finally {
                isLoading = false
                progressLoading.visibility = View.GONE
            }
        }
    }

    private fun loadPersons(offset: Int) {
        if (isLoading || context == null) return
        isLoading = true

        if (offset == 0) {
            progressLoading.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.getPersons(PAGE_SIZE, offset)
                }

                if (result != null) {
                    val items = parsePersons(result)
                    if (items.isNotEmpty()) {
                        allPersons.addAll(items)
                        hasMore = items.size >= PAGE_SIZE
                    } else {
                        hasMore = false
                    }

                    totalCount = allPersons.size
                    textPersonCount.text = "$totalCount persons"
                    adapter.submitList(allPersons.toList())
                    textEmpty.visibility = if (allPersons.isEmpty() && !isLoading) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (allPersons.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    if (offset == 0) {
                        textEmpty.text = "Failed to load persons"
                        textEmpty.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load persons failed: ${e.message}")
                if (offset == 0) {
                    textEmpty.text = "Error loading persons"
                    textEmpty.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
            } finally {
                isLoading = false
                progressLoading.visibility = View.GONE
            }
        }
    }

    private fun parsePersons(result: org.json.JSONObject): List<PersonItem> {
        val personsArray = result.optJSONArray("persons") ?: return emptyList()
        val items = mutableListOf<PersonItem>()
        for (i in 0 until personsArray.length()) {
            val obj = personsArray.getJSONObject(i)
            val rawName = if (obj.isNull("display_name")) null
                else obj.optString("display_name", "").let {
                    if (it == "null" || it.isEmpty()) null else it
                }
            items.add(
                PersonItem(
                    id = obj.getString("id"),
                    createdAt = obj.optString("created_at", ""),
                    lastSeenAt = obj.optString("last_seen_at", ""),
                    totalSightings = obj.optInt("total_sightings", 0),
                    isActive = obj.optBoolean("is_active", true),
                    displayName = rawName
                )
            )
        }
        return items
    }
}
