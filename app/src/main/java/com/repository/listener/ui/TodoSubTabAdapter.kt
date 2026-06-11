package com.repository.listener.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

class TodoSubTabAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> TodoPrimaryFragment()
            1 -> TodoSecondaryFragment()
            2 -> JobsFragment()
            3 -> AlarmFragment()
            else -> throw IllegalArgumentException("Invalid sub-tab position: $position")
        }
    }
}
