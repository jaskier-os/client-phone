package com.repository.listener.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.repository.listener.R
import com.repository.listener.service.ListenerService

class TodoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_todo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.todoTabLayout)
        val viewPager = view.findViewById<ViewPager2>(R.id.todoViewPager)

        val subTabAdapter = TodoSubTabAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        viewPager.adapter = subTabAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Primary"
                1 -> "Secondary"
                2 -> "Jobs"
                3 -> "Alarm"
                else -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 1) {
                    // Trigger refresh via broadcast (fragment picks it up)
                    requireContext().sendBroadcast(
                        Intent(ListenerService.ACTION_TELEGRAM_SAVED_REQ).apply {
                            setPackage(requireContext().packageName)
                        }
                    )
                }
                if (position == 2) {
                    requireContext().sendBroadcast(
                        Intent(ListenerService.ACTION_JOB_LIST_REQ).apply {
                            setPackage(requireContext().packageName)
                        }
                    )
                }
            }
        })
    }
}
