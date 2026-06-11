package com.repository.listener.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.repository.listener.BuildConfig

class ReidSubTabAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    // The "Phone Numbers" sub-tab is a region-specific ReID feature, hidden unless
    // ENABLE_REID_RU_TABS is set at build time.
    override fun getItemCount(): Int = if (BuildConfig.ENABLE_REID_RU_TABS) 2 else 1

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PeopleCatalogueFragment()
            1 -> PhoneNumbersFragment()
            else -> throw IllegalArgumentException("Invalid sub-tab position: $position")
        }
    }
}
