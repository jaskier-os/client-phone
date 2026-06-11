package com.repository.listener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.repository.listener.R

class PersonIntelFragment : Fragment() {

    private var personId = ""
    private var baseUrl = ""
    private var apiKey = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_person_intel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return

        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)

        viewPager.adapter = IntelPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Sherlock"
                1 -> "VK"
                else -> ""
            }
        }.attach()
    }

    private fun createChildArgs(): Bundle {
        return Bundle().apply {
            putString("person_id", personId)
            putString("base_url", baseUrl)
            putString("api_key", apiKey)
        }
    }

    private inner class IntelPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SherlockIntelFragment().apply { arguments = createChildArgs() }
                1 -> VkSearchFragment().apply { arguments = createChildArgs() }
                else -> throw IllegalArgumentException("Invalid tab position: $position")
            }
        }
    }
}
