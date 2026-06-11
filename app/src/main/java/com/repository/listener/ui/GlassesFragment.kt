package com.repository.listener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.repository.listener.R

class GlassesFragment : Fragment() {

    companion object {
        const val ACTION_TOGGLE_SIDELOADING = "com.repository.listener.TOGGLE_SIDELOADING"
        const val EXTRA_SIDELOADING_ENABLED = "sideloading_enabled"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_glasses, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.glassesTabLayout)
        val viewPager = view.findViewById<ViewPager2>(R.id.glassesViewPager)

        viewPager.adapter = GlassesSubTabAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Apps"
                1 -> "Files"
                2 -> "Settings"
                else -> ""
            }
        }.attach()
    }
}
