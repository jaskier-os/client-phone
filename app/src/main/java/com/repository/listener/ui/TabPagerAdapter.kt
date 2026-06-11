package com.repository.listener.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.repository.navigation.ui.NavigationFragment

class TabPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 7

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> TodoFragment()
            1 -> ChatsListFragment()
            2 -> ReidFragment()
            3 -> GlassesFragment()
            4 -> DesktopFragment()
            5 -> NavigationFragment()
            6 -> ConfigFragment()
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}
