package com.repository.listener.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.phone.CallLogReader
import com.repository.listener.phone.PhoneContact
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneNumbersFragment : Fragment() {

    companion object {
        private const val TAG = "PhoneNumbersFragment"
    }

    private lateinit var adapter: PhoneNumberAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var textEmpty: TextView
    private lateinit var editSearch: EditText
    private lateinit var btnSort: MaterialButton
    private lateinit var client: ReidAnalyticsClient
    private var imageBaseUrl: String = ""
    private var apiKey: String = ""

    private var allContacts: List<PhoneContact> = emptyList()
    private var currentSort: SortMode = SortMode.LAST_CALL

    private enum class SortMode { LAST_CALL, FREQUENCY, NAME }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_phone_numbers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerPhoneNumbers)
        textEmpty = view.findViewById(R.id.textEmpty)
        editSearch = view.findViewById(R.id.editSearch)
        btnSort = view.findViewById(R.id.btnSort)

        imageBaseUrl = AppConfig.getOrchestratorHttpUrl(requireContext())
        apiKey = AppConfig.getApiKey(requireContext())
        client = ReidAnalyticsClient(imageBaseUrl, apiKey)

        adapter = PhoneNumberAdapter(imageBaseUrl, apiKey, { contact ->
            onSherlockClick(contact)
        }) { personId ->
            val intent = Intent(requireContext(), PersonDetailActivity::class.java).apply {
                putExtra(PersonDetailActivity.EXTRA_PERSON_ID, personId)
            }
            startActivity(intent)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilterAndSort()
            }
        })

        btnSort.setOnClickListener { showSortMenu(it) }
    }

    override fun onResume() {
        super.onResume()
        loadCallLog()
    }

    private fun loadCallLog() {
        try {
            allContacts = CallLogReader.readCallLog(requireContext())
            applyFilterAndSort()
            lookupLinkedPersons()
        } catch (e: SecurityException) {
            LogCollector.e(TAG, "Call log permission denied: ${e.message}")
            textEmpty.text = "Call log permission required"
            textEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }
    }

    private fun applyFilterAndSort() {
        val query = editSearch.text.toString().trim().lowercase()
        var filtered = if (query.isEmpty()) {
            allContacts
        } else {
            allContacts.filter { contact ->
                contact.number.lowercase().contains(query) ||
                    (contact.name?.lowercase()?.contains(query) == true)
            }
        }

        filtered = when (currentSort) {
            SortMode.LAST_CALL -> filtered.sortedByDescending { it.lastCallDate }
            SortMode.FREQUENCY -> filtered.sortedByDescending { it.totalCalls }
            SortMode.NAME -> filtered.sortedBy { it.name?.lowercase() ?: "\uFFFF" }
        }

        adapter.submitList(filtered)
        textEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 0, 0, "Last call")
        popup.menu.add(0, 1, 1, "Frequency")
        popup.menu.add(0, 2, 2, "Name A-Z")

        popup.setOnMenuItemClickListener { item ->
            currentSort = when (item.itemId) {
                0 -> SortMode.LAST_CALL
                1 -> SortMode.FREQUENCY
                2 -> SortMode.NAME
                else -> SortMode.LAST_CALL
            }
            applyFilterAndSort()
            true
        }
        popup.show()
    }

    private fun lookupLinkedPersons() {
        val phones = allContacts.map { it.number }.distinct()
        if (phones.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lookupResult = withContext(Dispatchers.IO) {
                    client.batchPhoneLookup(phones)
                }
                if (lookupResult.isNotEmpty()) {
                    allContacts = allContacts.map { contact ->
                        val personId = lookupResult[contact.number]
                        if (personId != null) contact.copy(linkedPersonId = personId) else contact
                    }
                    applyFilterAndSort()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Phone lookup failed: ${e.message}")
            }
        }
    }

    private fun onSherlockClick(contact: PhoneContact) {
        val ctx = requireContext()

        Thread {
            val result = client.searchPhone(contact.number)
            requireActivity().runOnUiThread {
                if (result != null) {
                    val intent = Intent(ctx, SherlockResultActivity::class.java).apply {
                        putExtra(SherlockResultActivity.EXTRA_RESULT_JSON, result.toString())
                        putExtra(SherlockResultActivity.EXTRA_PHONE_NUMBER, contact.number)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(ctx, "Sherlock search failed", Toast.LENGTH_SHORT).show()
                }
                adapter.clearSherlockProgress()
            }
        }.start()
    }
}
