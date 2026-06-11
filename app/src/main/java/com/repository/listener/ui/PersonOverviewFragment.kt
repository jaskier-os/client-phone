package com.repository.listener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonOverviewFragment : Fragment() {

    companion object {
        private const val TAG = "PersonOverview"
    }

    private lateinit var client: ReidAnalyticsClient
    private lateinit var snapshotAdapter: SnapshotAdapter
    private var personId = ""
    private var baseUrl = ""
    private var apiKey = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_person_overview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return
        client = ReidAnalyticsClient(baseUrl, apiKey)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSnapshots)
        val progress = view.findViewById<ProgressBar>(R.id.progressLoading)
        val layoutEmpty = view.findViewById<View>(R.id.layoutEmpty)
        val textEmpty = view.findViewById<TextView>(R.id.textEmpty)
        val chipFirst = view.findViewById<TextView>(R.id.chipFirstSeen)
        val chipLast = view.findViewById<TextView>(R.id.chipLastSeen)
        val chipSightings = view.findViewById<TextView>(R.id.chipSightings)

        snapshotAdapter = SnapshotAdapter(apiKey) { snapshot ->
            FullscreenImageDialog.newInstance(snapshot.url, apiKey)
                .show(parentFragmentManager, "fullscreen")
        }
        recycler.layoutManager = GridLayoutManager(requireContext(), 3)
        recycler.adapter = snapshotAdapter

        progress.visibility = View.VISIBLE
        chipFirst.text = "Loading..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val personResult = withContext(Dispatchers.IO) { client.getPerson(personId) }
                if (personResult != null) {
                    chipFirst.text = "First: ${formatDate(personResult.optString("created_at", ""))}"
                    chipLast.text = "Last: ${formatDate(personResult.optString("last_seen_at", ""))}"
                    chipSightings.text = "${personResult.optInt("total_sightings", 0)} sightings"
                }

                val snapshotsResult = withContext(Dispatchers.IO) { client.getPersonSnapshots(personId) }
                LogCollector.d(TAG, "Snapshots response: ${snapshotsResult.toString().take(500)}")
                if (snapshotsResult != null) {
                    val arr = snapshotsResult.optJSONArray("sightings")
                    val items = mutableListOf<SnapshotItem>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val snapshotPath = obj.optString("snapshot_path", "")
                            LogCollector.d(TAG, "Snapshot path: $snapshotPath for sighting ${obj.optString("id", "?")}")
                            if (snapshotPath.isNotEmpty()) {
                                val imageUrl = when {
                                    snapshotPath.startsWith("data:") -> snapshotPath
                                    snapshotPath.startsWith("http") -> snapshotPath
                                    else -> "$baseUrl$snapshotPath"
                                }
                                items.add(SnapshotItem(
                                    url = imageUrl,
                                    cameraId = obj.optString("camera_id", "?"),
                                    timestamp = obj.optString("detected_at", "")
                                ))
                            }
                        }
                    }
                    snapshotAdapter.submitList(items)
                    if (items.isEmpty()) {
                        textEmpty.text = "No snapshots - check Overview for person image"
                        layoutEmpty.visibility = View.VISIBLE
                    } else {
                        layoutEmpty.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load overview failed: ${e.message}")
                textEmpty.text = "Failed to load"
                layoutEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun formatDate(dateStr: String): String {
        return try {
            val temporal = java.time.LocalDateTime.parse(dateStr.take(19))
            temporal.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } catch (_: Exception) {
            dateStr.take(10)
        }
    }
}
