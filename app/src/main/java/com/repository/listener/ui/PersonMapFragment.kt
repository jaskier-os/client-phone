package com.repository.listener.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButtonToggleGroup
import com.repository.listener.R
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PersonMapFragment : Fragment() {

    companion object {
        private const val TAG = "PersonMap"
        private const val PAGE_SIZE = 50
    }

    private lateinit var client: ReidAnalyticsClient
    private lateinit var timelineAdapter: MapSightingAdapter
    private var webViewMap: WebView? = null
    private var personId = ""
    private var baseUrl = ""
    private var apiKey = ""
    private var mapReady = false
    private var pendingJson: String? = null
    private var showTrajectory = true

    private var allSightings = mutableListOf<SightingItem>()
    private var hiddenIndices = mutableSetOf<Int>()
    private var sortMode = "date-desc"
    private var hasMore = true
    private var isLoadingMore = false
    private var textSightingCount: TextView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_person_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return
        client = ReidAnalyticsClient(baseUrl, apiKey)

        val progressMap = view.findViewById<ProgressBar>(R.id.progressMap)
        val textNoLocation = view.findViewById<TextView>(R.id.textNoLocation)
        val textEmptyTimeline = view.findViewById<TextView>(R.id.textEmptyTimeline)
        val switchPath = view.findViewById<SwitchCompat>(R.id.switchShowPath)
        val sortToggle = view.findViewById<MaterialButtonToggleGroup>(R.id.sortToggle)
        val recyclerTimeline = view.findViewById<RecyclerView>(R.id.recyclerTimeline)
        textSightingCount = view.findViewById(R.id.textSightingCount)
        webViewMap = view.findViewById(R.id.webViewMap)

        // Prevent ViewPager2 from stealing map touch events
        webViewMap?.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        // Bottom sheet -- capped at 50%
        val bottomSheet = view.findViewById<LinearLayout>(R.id.bottomSheet)
        val sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        sheetBehavior.peekHeight = (56 * resources.displayMetrics.density).toInt()
        sheetBehavior.isHideable = false
        sheetBehavior.isFitToContents = false
        sheetBehavior.halfExpandedRatio = 0.5f
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                }
            }
            override fun onSlide(sheet: View, offset: Float) {}
        })

        // Timeline adapter
        timelineAdapter = MapSightingAdapter(
            onSightingClick = { sighting ->
                if (sighting.latitude != null && sighting.longitude != null) {
                    webViewMap?.evaluateJavascript("flyTo(${sighting.latitude}, ${sighting.longitude})", null)
                    // Find index in the current visible map list for correct highlight
                    val visibleList = getVisibleLocatedSightings()
                    val mapIdx = visibleList.indexOfFirst { it.id == sighting.id }
                    if (mapIdx >= 0) {
                        webViewMap?.evaluateJavascript("highlightSighting($mapIdx)", null)
                    }
                }
            },
            onVisibilityToggle = { index ->
                if (hiddenIndices.contains(index)) {
                    hiddenIndices.remove(index)
                } else {
                    hiddenIndices.add(index)
                }
                timelineAdapter.setHiddenIndices(hiddenIndices)
                updateMapSightings()
                updateSightingCount()
            }
        )
        recyclerTimeline.layoutManager = LinearLayoutManager(requireContext())
        recyclerTimeline.adapter = timelineAdapter

        // Infinite scroll for timeline
        recyclerTimeline.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val total = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (hasMore && !isLoadingMore && lastVisible >= total - 5) {
                    loadMoreSightings()
                }
            }
        })

        // Show path toggle
        switchPath.setOnCheckedChangeListener { _, checked ->
            showTrajectory = checked
            webViewMap?.evaluateJavascript("toggleTrajectory($checked)", null)
        }

        // Sort toggle
        sortToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                sortMode = when (checkedId) {
                    R.id.btnSortLatest -> "date-desc"
                    R.id.btnSortOldest -> "date-asc"
                    R.id.btnSortCamera -> "camera"
                    else -> "date-desc"
                }
                updateTimeline()
            }
        }

        // Setup map
        webViewMap?.settings?.javaScriptEnabled = true
        webViewMap?.settings?.domStorageEnabled = true
        webViewMap?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                webViewMap?.evaluateJavascript("initMap('$baseUrl', '$apiKey')", null)
                mapReady = true
                pendingJson?.let { json ->
                    webViewMap?.evaluateJavascript("setSightingsWithTrajectory($json, $showTrajectory)", null)
                    pendingJson = null
                }
                progressMap.visibility = View.GONE
            }
        }
        webViewMap?.loadUrl("file:///android_asset/person_map.html")

        // Load initial sightings
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { fetchSightings(0) }
                allSightings.addAll(items)
                hasMore = items.size >= PAGE_SIZE
                updateTimeline()
                updateMapSightings()
                updateSightingCount()

                val located = allSightings.any { it.latitude != null && it.longitude != null }
                if (!located) {
                    textNoLocation.visibility = View.VISIBLE
                    webViewMap?.visibility = View.GONE
                }
                textEmptyTimeline.visibility = if (allSightings.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load sightings failed: ${e.message}")
                textEmptyTimeline.text = "Error loading sightings"
                textEmptyTimeline.visibility = View.VISIBLE
            }
        }
    }

    private fun loadMoreSightings() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { fetchSightings(allSightings.size) }
                allSightings.addAll(items)
                hasMore = items.size >= PAGE_SIZE
                updateTimeline()
                updateMapSightings()
                updateSightingCount()
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load more sightings failed: ${e.message}")
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun fetchSightings(offset: Int): List<SightingItem> {
        val result = client.getPersonSightings(personId, PAGE_SIZE, offset) ?: return emptyList()
        val arr = result.optJSONArray("sightings") ?: return emptyList()
        val items = mutableListOf<SightingItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(SightingItem(
                id = obj.optString("id", ""),
                cameraId = obj.optString("camera_id", "unknown"),
                detectedAt = obj.optString("detected_at", ""),
                confidenceScore = obj.optDouble("confidence_score", 0.0),
                latitude = if (obj.has("person_latitude") && !obj.isNull("person_latitude")) obj.getDouble("person_latitude") else null,
                longitude = if (obj.has("person_longitude") && !obj.isNull("person_longitude")) obj.getDouble("person_longitude") else null,
                headingDegrees = if (obj.has("heading_degrees") && !obj.isNull("heading_degrees")) obj.getDouble("heading_degrees") else null,
                gazeDegrees = if (obj.has("gaze_degrees") && !obj.isNull("gaze_degrees")) obj.getDouble("gaze_degrees") else null,
                snapshotPath = obj.optString("snapshot_path", null)
            ))
        }
        return items
    }

    private fun updateTimeline() {
        val sorted = sortSightings(allSightings)
        val items = groupByDay(sorted)
        timelineAdapter.submitList(items)
        timelineAdapter.setHiddenIndices(hiddenIndices)
    }

    private fun getVisibleLocatedSightings(): List<SightingItem> {
        return allSightings.filterIndexed { idx, s ->
            !hiddenIndices.contains(idx) && s.latitude != null && s.longitude != null
        }
    }

    private fun updateMapSightings() {
        val visible = getVisibleLocatedSightings()
        val jsonArray = JSONArray()
        visible.forEach { s ->
            val obj = JSONObject()
            obj.put("camera_id", s.cameraId)
            obj.put("detected_at", s.detectedAt)
            obj.put("confidence_score", s.confidenceScore)
            obj.put("latitude", s.latitude)
            obj.put("longitude", s.longitude)
            if (s.headingDegrees != null) obj.put("heading_degrees", s.headingDegrees)
            if (s.gazeDegrees != null) obj.put("gaze_degrees", s.gazeDegrees)
            jsonArray.put(obj)
        }
        val json = jsonArray.toString()
        if (mapReady) {
            webViewMap?.evaluateJavascript("setSightingsWithTrajectory($json, $showTrajectory)", null)
        } else {
            pendingJson = json
        }
    }

    private fun updateSightingCount() {
        val visible = allSightings.size - hiddenIndices.size
        textSightingCount?.text = "$visible / ${allSightings.size}"
    }

    private fun sortSightings(sightings: List<SightingItem>): List<SightingItem> {
        return when (sortMode) {
            "date-desc" -> sightings.sortedByDescending { it.detectedAt }
            "date-asc" -> sightings.sortedBy { it.detectedAt }
            "camera" -> sightings.sortedBy { it.cameraId }
            else -> sightings
        }
    }

    private fun groupByDay(sorted: List<SightingItem>): List<MapTimelineItem> {
        val items = mutableListOf<MapTimelineItem>()
        // Build id->index map for O(1) lookup
        val idToIndex = mutableMapOf<String, Int>()
        allSightings.forEachIndexed { idx, s -> idToIndex[s.id] = idx }

        var currentDay = ""
        sorted.forEach { sighting ->
            val origIdx = if (sighting.id.isNotEmpty()) {
                idToIndex[sighting.id] ?: allSightings.indexOf(sighting)
            } else {
                allSightings.indexOf(sighting)
            }
            val day = try {
                val temporal = java.time.LocalDate.parse(sighting.detectedAt.take(10))
                temporal.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy"))
            } catch (_: Exception) {
                sighting.detectedAt.take(10)
            }
            if (day != currentDay) {
                currentDay = day
                items.add(MapTimelineItem.DayHeader(day))
            }
            items.add(MapTimelineItem.Sighting(sighting, origIdx))
        }
        return items
    }

    override fun onDestroyView() {
        webViewMap?.destroy()
        webViewMap = null
        super.onDestroyView()
    }
}
