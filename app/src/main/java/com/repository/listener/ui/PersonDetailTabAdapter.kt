package com.repository.listener.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.repository.listener.BuildConfig

class PersonDetailTabAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle,
    private val personId: String,
    private val baseUrl: String,
    private val apiKey: String
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    // The "Intel" tab (position 3) is a region-specific ReID feature, hidden unless
    // ENABLE_REID_RU_TABS is set at build time.
    override fun getItemCount(): Int = if (BuildConfig.ENABLE_REID_RU_TABS) 4 else 3

    override fun createFragment(position: Int): Fragment {
        val args = Bundle().apply {
            putString("person_id", personId)
            putString("base_url", baseUrl)
            putString("api_key", apiKey)
        }
        return when (position) {
            0 -> PersonOverviewFragment().apply { arguments = args }
            1 -> PersonMapFragment().apply { arguments = args }
            2 -> PersonSimilarFragment().apply { arguments = args }
            3 -> PersonIntelFragment().apply { arguments = args }
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}
