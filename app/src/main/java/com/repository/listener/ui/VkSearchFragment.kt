package com.repository.listener.ui

import android.util.Log
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.repository.listener.R
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.ImageCacheUtil
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class VkSearchFragment : Fragment() {

    companion object {
        private const val TAG = "VkSearch"
        private const val SIMILARITY_HIGH = 80
        private const val SIMILARITY_MEDIUM = 60
        private const val COLOR_SIM_HIGH = "#FFb8bb26"
        private const val COLOR_SIM_MEDIUM = "#FFfabd2f"
        private const val COLOR_SIM_LOW = "#FFfb4934"
        private const val COLOR_BADGE_TEXT = "#FF282828"
        private const val PHOTO_SIZE_DP = 80
        private const val PHOTO_RADIUS_DP = 4
    }

    private lateinit var client: ReidAnalyticsClient
    private var personId = ""
    private var baseUrl = ""
    private var apiKey = ""

    private lateinit var cardsContainer: LinearLayout
    private lateinit var layoutLoading: View
    private lateinit var layoutEmpty: View
    private lateinit var scrollContent: View
    private lateinit var btnSearchVk: MaterialButton
    private lateinit var btnRerunVk: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vk_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return
        client = ReidAnalyticsClient(baseUrl, apiKey)

        cardsContainer = view.findViewById(R.id.layoutCards)
        layoutLoading = view.findViewById(R.id.layoutLoading)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        scrollContent = view.findViewById(R.id.scrollContent)
        btnSearchVk = view.findViewById(R.id.btnSearchVk)
        btnRerunVk = view.findViewById(R.id.btnRerunVk)

        btnSearchVk.setOnClickListener { runVkSearch() }
        btnRerunVk.setOnClickListener { runVkSearch() }

        loadVkData()
    }

    private fun loadVkData() {
        layoutLoading.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        btnRerunVk.visibility = View.GONE
        cardsContainer.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val person = withContext(Dispatchers.IO) { client.getPerson(personId) }
                layoutLoading.visibility = View.GONE

                if (person == null) {
                    layoutEmpty.visibility = View.VISIBLE
                    return@launch
                }

                val infoFound = person.optJSONObject("information_found")
                Log.d(TAG, "infoFound keys: ${infoFound?.keys()?.asSequence()?.toList()}")
                val vkData = infoFound?.optJSONObject("search4faces_vk")
                Log.d(TAG, "vkData: ${vkData?.toString()?.take(200)}")

                if (vkData == null || vkData.length() == 0) {
                    Log.d(TAG, "No VK data, showing empty")
                    layoutEmpty.visibility = View.VISIBLE
                    return@launch
                }

                scrollContent.visibility = View.VISIBLE
                btnRerunVk.visibility = View.VISIBLE
                renderVkResults(vkData)

            } catch (e: Exception) {
                LogCollector.e(TAG, "Load VK data failed: ${e.message}")
                layoutLoading.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun runVkSearch() {
        btnSearchVk.isEnabled = false
        btnRerunVk.isEnabled = false
        layoutEmpty.visibility = View.GONE
        scrollContent.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.searchPersonInfo(personId, "photo", "", null, "search4faces")
                }

                if (result != null && result.optBoolean("success", false)) {
                    Toast.makeText(requireContext(), "VK search complete", Toast.LENGTH_SHORT).show()
                    loadVkData()
                    parentFragmentManager.setFragmentResult("intel_updated", Bundle.EMPTY)
                } else {
                    val errorType = result?.optString("type", "unknown") ?: "error"
                    LogCollector.e(TAG, "VK search failed: type=$errorType")
                    Toast.makeText(requireContext(), "VK search failed: $errorType", Toast.LENGTH_SHORT).show()
                    layoutLoading.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "VK search error: ${e.message}")
                Toast.makeText(requireContext(), "VK search error", Toast.LENGTH_SHORT).show()
                layoutLoading.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            } finally {
                btnSearchVk.isEnabled = true
                btnRerunVk.isEnabled = true
            }
        }
    }

    private fun renderVkResults(vkData: JSONObject) {
        val persons = vkData.optJSONArray("persons") ?: return
        if (persons.length() == 0) return
        Log.d(TAG, "Rendering ${persons.length()} VK results, first: ${persons.optJSONObject(0)?.toString()?.take(200)}")

        val total = vkData.optInt("totalResults", persons.length())
        cardsContainer.addView(buildSectionHeader("VK Matches ($total)"))

        for (i in 0 until persons.length()) {
            val person = persons.optJSONObject(i) ?: continue
            cardsContainer.addView(buildVkMatchCard(person))
        }
    }

    private fun buildVkMatchCard(person: JSONObject): MaterialCardView {
        val ctx = requireContext()
        val card = createCard()
        val content = createCardContent()

        // Top row: name + similarity badge
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = person.optString("fullName", "").takeIf { it.isNotEmpty() && it != "null" }
            ?: person.optString("firstName", "").takeIf { it.isNotEmpty() && it != "null" }
            ?: ""

        if (name.isNotEmpty()) {
            topRow.addView(TextView(ctx).apply {
                text = name
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        val similarity = person.optDouble("similarity", -1.0)
        if (similarity >= 0) {
            val pct = if (similarity <= 1.0) (similarity * 100).toInt() else similarity.toInt()
            topRow.addView(buildSimilarityBadge(pct.coerceIn(0, 100)))
        }

        content.addView(topRow)

        // Details row: age | city | country | DOB
        val details = mutableListOf<String>()
        val age = person.optInt("age", 0)
        if (age > 0) details.add("${age}y")
        val city = person.optString("city", "").takeIf { it.isNotEmpty() && it != "null" }
        if (city != null) details.add(city)
        val country = person.optString("country", "").takeIf { it.isNotEmpty() && it != "null" }
        if (country != null && country != city) details.add(country)
        val dob = person.optString("dateOfBirth", "").takeIf { it.isNotEmpty() && it != "null" }
        if (dob != null) details.add(dob)

        if (details.isNotEmpty()) {
            content.addView(TextView(ctx).apply {
                text = details.joinToString(" | ")
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setPadding(0, dpToPx(4), 0, 0)
            })
        }

        // VK link
        val vkUrl = run {
            val vkObj = person.optJSONObject("vk")
            vkObj?.optString("url", "") ?: person.optString("vk", "")
        }
        if (vkUrl.isNotEmpty() && vkUrl != "null") {
            content.addView(buildClickableLink("VK: $vkUrl", vkUrl))
        }

        // Photo thumbnail from search4faces URL
        val photoUrl = person.optString("photoUrl", "")
        if (photoUrl.isNotEmpty()) {
            val photoSize = dpToPx(PHOTO_SIZE_DP)
            val photoRadius = dpToPx(PHOTO_RADIUS_DP).toFloat()
            val imageView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(photoSize, photoSize).apply {
                    topMargin = dpToPx(8)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(ctx, R.color.gbx_bg2))
                    cornerRadius = photoRadius
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, photoRadius)
                    }
                }
            }

            // Photos are saved as osint assets on the backend
            Log.d(TAG, "Loading VK photo: baseUrl=$baseUrl photoUrl=$photoUrl")
            ImageCacheUtil.loadOsintPhoto(imageView, baseUrl, apiKey, photoUrl)

            // Open photo page or VK profile on click
            val photoPageUrl = person.optString("photoPageUrl", "").ifEmpty { vkUrl }
            if (photoPageUrl.isNotEmpty()) {
                imageView.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photoPageUrl)))
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Failed to open URL: ${e.message}")
                    }
                }
            }

            content.addView(imageView)
        }

        card.addView(content)
        return card
    }

    // === UI Builders ===

    private fun createCard(): MaterialCardView {
        val ctx = requireContext()
        return MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.gbx_bg1))
            radius = dpToPx(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
    }

    private fun createCardContent(): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }
    }

    private fun buildSectionHeader(title: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(4), dpToPx(8), 0, dpToPx(8))
        }
    }

    private fun buildSimilarityBadge(pct: Int): TextView {
        val ctx = requireContext()
        val bgColor = when {
            pct >= SIMILARITY_HIGH -> Color.parseColor(COLOR_SIM_HIGH)
            pct >= SIMILARITY_MEDIUM -> Color.parseColor(COLOR_SIM_MEDIUM)
            else -> Color.parseColor(COLOR_SIM_LOW)
        }

        return TextView(ctx).apply {
            text = "${pct}%"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(COLOR_BADGE_TEXT))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
        }
    }

    private fun buildClickableLink(label: String, url: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
            textSize = 13f
            setPadding(0, dpToPx(4), 0, 0)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to open URL: ${e.message}")
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
