package com.repository.listener.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonSimilarFragment : Fragment() {

    companion object {
        private const val TAG = "PersonSimilar"
    }

    private lateinit var client: ReidAnalyticsClient
    private var personId = ""
    private var baseUrl = ""
    private var apiKey = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_person_similar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return
        client = ReidAnalyticsClient(baseUrl, apiKey)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSimilar)
        val layoutLoading = view.findViewById<View>(R.id.layoutLoading)
        val layoutEmpty = view.findViewById<View>(R.id.layoutEmpty)
        val textEmpty = view.findViewById<TextView>(R.id.textEmpty)

        val adapter = SimilarPersonAdapter(baseUrl, apiKey) { person ->
            val intent = Intent(requireContext(), PersonDetailActivity::class.java).apply {
                putExtra(PersonDetailActivity.EXTRA_PERSON_ID, person.id)
            }
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        layoutLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.getSimilarPersons(personId, 0.3f, 10)
                }
                if (result != null) {
                    LogCollector.d(TAG, "Similar response: ${result.toString().take(500)}")
                    val arr = result.optJSONArray("similar_persons")
                        ?: result.optJSONArray("persons")
                    val items = mutableListOf<SimilarPersonItem>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            items.add(SimilarPersonItem(
                                id = obj.getString("id"),
                                similarity = obj.optDouble("similarity", 0.0)
                            ))
                        }
                    }
                    adapter.submitList(items)
                    layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Load similar persons failed: ${e.message}")
                textEmpty.text = "Failed to load"
                layoutEmpty.visibility = View.VISIBLE
            } finally {
                layoutLoading.visibility = View.GONE
            }
        }
    }
}
