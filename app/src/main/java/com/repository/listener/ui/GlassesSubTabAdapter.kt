package com.repository.listener.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

class GlassesSubTabAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> GlassesAppsFragment()
            1 -> GlassesFilesFragment()
            2 -> GlassesSettingsFragment()
            else -> throw IllegalArgumentException("Invalid sub-tab position: $position")
        }
    }
}
