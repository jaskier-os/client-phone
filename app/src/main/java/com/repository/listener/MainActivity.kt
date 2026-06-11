package com.repository.listener

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.repository.listener.security.BiometricLockManager
import com.repository.listener.service.ListenerService
import com.repository.listener.ui.TabPagerAdapter

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "listener_main"
        private const val KEY_BATTERY_PROMPTED = "battery_opt_prompted"
        private const val KEY_AUTOSTART_PROMPTED = "autostart_prompted"
    }

    private lateinit var lockOverlay: View

    private val tabIcons = intArrayOf(
        R.drawable.ic_tab_calendar,
        R.drawable.ic_tab_chat,
        R.drawable.ic_tab_face,
        R.drawable.ic_tab_glasses,
        R.drawable.ic_monitor,
        R.drawable.ic_tab_map,
        R.drawable.ic_tab_config
    )

    private val tabContentDescriptions = intArrayOf(
        R.string.tab_desc_todo,
        R.string.tab_desc_chats,
        R.string.tab_desc_reid,
        R.string.tab_desc_glasses,
        R.string.tab_desc_desktop,
        R.string.tab_desc_navigation,
        R.string.tab_desc_config
    )

    private val tabViewIds = intArrayOf(
        R.id.tab_todo,
        R.id.tab_chats,
        R.id.tab_reid,
        R.id.tab_glasses,
        R.id.tab_desktop,
        R.id.tab_navigation,
        R.id.tab_config
    )

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Background location denied -- sightings will lack GPS", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
        }
        // Request background location separately after fine location is granted (Android 10+ requirement)
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ListenerService::class.java).apply {
                action = ListenerService.ACTION_SETUP_PROJECTION
                putExtra(ListenerService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ListenerService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = TabPagerAdapter(this)
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.setIcon(tabIcons[position])
            tab.contentDescription = getString(tabContentDescriptions[position])
            tab.view.id = tabViewIds[position]
        }.attach()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val distance = Math.abs(tab.position - viewPager.currentItem)
                viewPager.setCurrentItem(tab.position, distance <= 1)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        lockOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(lockOverlay)

        requestPermissions()
        startListenerService()
    }

    override fun onResume() {
        super.onResume()
        if (BiometricLockManager.isAuthRequired() && BiometricLockManager.isAvailable(this)) {
            lockOverlay.visibility = View.VISIBLE
            BiometricLockManager.authenticate(
                activity = this,
                onSuccess = { lockOverlay.visibility = View.GONE },
                onFailed = { finish() }
            )
        } else {
            lockOverlay.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        if (BuildConfig.REQUIRE_BIOMETRIC && BiometricLockManager.isAvailable(this)) {
            lockOverlay.visibility = View.VISIBLE
        }
    }

    private fun startListenerService() {
        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        requestBatteryOptimizationExemption()
        openMiuiAutoStart()
        // If MainActivity was cold-started by the service to ask for projection
        // consent, onNewIntent never fires -- only the launching Intent in
        // getIntent() carries the action. Honor it here too, and clear the
        // action so a rotation/recreate doesn't replay the prompt.
        if (intent?.action == ListenerService.ACTION_REQUEST_PROJECTION) {
            requestMediaProjection()
            intent.action = null
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ListenerService.ACTION_REQUEST_PROJECTION) {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        if (ListenerService.hasProjection) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
    }

    private fun openMiuiAutoStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOSTART_PROMPTED, false)) return

        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            startActivity(intent)
            prefs.edit().putBoolean(KEY_AUTOSTART_PROMPTED, true).apply()
        } catch (e: Exception) {
            // Not MIUI or activity not found -- mark as prompted so we don't retry
            prefs.edit().putBoolean(KEY_AUTOSTART_PROMPTED, true).apply()
        }
    }
}
