package com.repository.listener.ui

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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.repository.listener.R
import com.repository.listener.network.ReidAnalyticsClient
import com.repository.listener.util.ImageCacheUtil
import com.repository.listener.util.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SherlockIntelFragment : Fragment() {

    companion object {
        private const val TAG = "SherlockIntel"
        private const val SIMILARITY_HIGH = 80
        private const val SIMILARITY_MEDIUM = 60
        private const val COLOR_SIM_HIGH = "#FFb8bb26"
        private const val COLOR_SIM_MEDIUM = "#FFfabd2f"
        private const val COLOR_SIM_LOW = "#FFfb4934"
        private const val COLOR_BADGE_TEXT = "#FF282828"
        private const val PHOTO_SIZE_DP = 80
        private const val PHOTO_SPACING_DP = 6
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
    private lateinit var btnSearchFace: MaterialButton
    private lateinit var editBotTag: TextInputEditText
    private lateinit var layoutBottomControls: View
    private lateinit var editBotTagBottom: TextInputEditText
    private lateinit var btnRerunFace: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_sherlock_intel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        personId = arguments?.getString("person_id") ?: return
        baseUrl = arguments?.getString("base_url") ?: return
        apiKey = arguments?.getString("api_key") ?: return
        client = ReidAnalyticsClient(baseUrl, apiKey)

        cardsContainer = view.findViewById(R.id.layoutIntelCards)
        layoutLoading = view.findViewById(R.id.layoutLoading)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        scrollContent = view.findViewById(R.id.scrollContent)
        btnSearchFace = view.findViewById(R.id.btnSearchFace)
        editBotTag = view.findViewById(R.id.editBotTag)
        layoutBottomControls = view.findViewById(R.id.layoutBottomControls)
        editBotTagBottom = view.findViewById(R.id.editBotTagBottom)
        btnRerunFace = view.findViewById(R.id.btnRerunFace)

        val prefs = requireContext().getSharedPreferences("sherlock_prefs", android.content.Context.MODE_PRIVATE)
        val savedTag = prefs.getString("botTag", "") ?: ""
        editBotTag.setText(savedTag)
        editBotTagBottom.setText(savedTag)

        val tagWatcher = { source: TextInputEditText, mirror: TextInputEditText ->
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val tag = s?.toString()?.trim() ?: ""
                    prefs.edit().putString("botTag", tag).apply()
                    if (mirror.text?.toString()?.trim() != tag) mirror.setText(tag)
                }
            }
        }
        editBotTag.addTextChangedListener(tagWatcher(editBotTag, editBotTagBottom))
        editBotTagBottom.addTextChangedListener(tagWatcher(editBotTagBottom, editBotTag))

        btnSearchFace.setOnClickListener { runGather() }
        btnRerunFace.setOnClickListener { runGather() }

        loadIntel()
    }

    private fun loadIntel() {
        layoutLoading.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        layoutBottomControls.visibility = View.GONE
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
                val searchKeys = person.optJSONArray("search_keys")

                // Check if there's any sherlock data or identifiers
                val hasSherlockData = infoFound?.optJSONObject("sherlock_photo") != null ||
                    infoFound?.optJSONObject("sherlock_phone") != null
                val hasSearchKeys = searchKeys != null && searchKeys.length() > 0
                val hasOtherSources = run {
                    if (infoFound == null) return@run false
                    val handled = setOf("sherlock_photo", "sherlock_phone", "search4faces_vk")
                    val keys = infoFound.keys()
                    while (keys.hasNext()) {
                        if (keys.next() !in handled) return@run true
                    }
                    false
                }

                if (!hasSherlockData && !hasSearchKeys && !hasOtherSources) {
                    layoutEmpty.visibility = View.VISIBLE
                    return@launch
                }

                scrollContent.visibility = View.VISIBLE
                layoutBottomControls.visibility = View.VISIBLE

                val photoData = infoFound?.optJSONObject("sherlock_photo")
                if (photoData != null) {
                    buildPhotoMatchesSection(photoData)
                }

                val phoneData = infoFound?.optJSONObject("sherlock_phone")
                if (phoneData != null) {
                    cardsContainer.addView(buildPhoneIntelCard(phoneData))
                }

                if (hasSearchKeys) {
                    cardsContainer.addView(buildIdentifiersCard(searchKeys!!, infoFound))
                }

                if (infoFound != null) {
                    val handledSources = setOf("sherlock_photo", "sherlock_phone", "search4faces_vk")
                    val keys = infoFound.keys()
                    while (keys.hasNext()) {
                        val source = keys.next()
                        if (source in handledSources) continue
                        val data = infoFound.optJSONObject(source) ?: continue
                        if (data.length() > 0) {
                            cardsContainer.addView(buildOtherSourceCard(source, data))
                        }
                    }
                }

            } catch (e: Exception) {
                LogCollector.e(TAG, "Load intel failed: ${e.message}")
                layoutLoading.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun runGather() {
        btnSearchFace.isEnabled = false
        layoutEmpty.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val botTag = editBotTag.text?.toString()?.trim()?.ifEmpty { null }
                val result = withContext(Dispatchers.IO) {
                    client.searchPersonInfo(personId, "photo", "", botTag)
                }

                if (result != null && result.optBoolean("success", false)) {
                    Toast.makeText(requireContext(), "Intel gathered", Toast.LENGTH_SHORT).show()
                    loadIntel()
                    parentFragmentManager.setFragmentResult("intel_updated", Bundle.EMPTY)
                } else {
                    val errorType = result?.optString("type", "unknown") ?: "error"
                    LogCollector.e(TAG, "Gather failed: type=$errorType")
                    Toast.makeText(requireContext(), "Gather failed: $errorType", Toast.LENGTH_SHORT).show()
                    layoutLoading.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Gather error: ${e.message}")
                Toast.makeText(requireContext(), "Gather error", Toast.LENGTH_SHORT).show()
                layoutLoading.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            } finally {
                btnSearchFace.isEnabled = true
            }
        }
    }

    // === Photo Matches ===

    private fun buildPhotoMatchesSection(photoData: JSONObject) {
        val persons = photoData.optJSONArray("persons") ?: return
        if (persons.length() == 0) return

        cardsContainer.addView(buildSectionHeader("Photo Matches"))

        for (i in 0 until persons.length()) {
            val person = persons.optJSONObject(i) ?: continue
            cardsContainer.addView(buildPhotoMatchCard(person))
        }
    }

    private fun buildPhotoMatchCard(person: JSONObject): MaterialCardView {
        val ctx = requireContext()
        val card = createCard()
        val content = createCardContent()

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = person.optString("fullName", "").ifEmpty {
            listOf(
                person.optString("firstName", ""),
                person.optString("lastName", "")
            ).filter { it.isNotEmpty() }.joinToString(" ")
        }.trim()

        if (name.isNotEmpty()) {
            topRow.addView(TextView(ctx).apply {
                text = name
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        val rawSimilarity = person.optDouble("similarity", -1.0)
        if (rawSimilarity >= 0) {
            val pct = if (rawSimilarity <= 1.0) (rawSimilarity * 100).toInt() else rawSimilarity.toInt()
            topRow.addView(buildSimilarityBadge(pct.coerceIn(0, 100)))
        }

        content.addView(topRow)

        val details = mutableListOf<String>()
        val age = person.optInt("age", 0)
        if (age > 0) details.add("${age}y")
        val city = person.optString("city", "")
        if (city.isNotEmpty()) details.add(city)
        val phone = person.optString("phone", "")
        if (phone.isNotEmpty()) details.add(phone)

        if (details.isNotEmpty()) {
            content.addView(TextView(ctx).apply {
                text = details.joinToString(" | ")
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setPadding(0, dpToPx(4), 0, 0)
            })
        }

        val vkUrl = person.optString("vkUrl", "").ifEmpty {
            val vkObj = person.optJSONObject("vk")
            vkObj?.optString("url", "") ?: person.optString("vk", "")
        }
        if (vkUrl.isNotEmpty()) {
            content.addView(buildClickableLink("VK: $vkUrl", vkUrl))
        }

        val photos = person.optJSONArray("photos")
        if (photos != null && photos.length() > 0) {
            content.addView(buildPhotoRow(photos))
        }

        card.addView(content)
        return card
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

    private fun buildPhotoRow(photos: JSONArray): HorizontalScrollView {
        val ctx = requireContext()
        val scrollView = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
            isHorizontalScrollBarEnabled = false
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val photoSize = dpToPx(PHOTO_SIZE_DP)
        val photoSpacing = dpToPx(PHOTO_SPACING_DP)
        val photoRadius = dpToPx(PHOTO_RADIUS_DP).toFloat()

        for (i in 0 until photos.length()) {
            val filename = photos.optString(i, "")
            if (filename.isEmpty()) continue

            val imageView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(photoSize, photoSize).apply {
                    if (i > 0) marginStart = photoSpacing
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(ctx, R.color.gbx_bg2))
                    cornerRadius = photoRadius
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, photoRadius)
                    }
                }
            }

            ImageCacheUtil.loadOsintPhoto(imageView, baseUrl, apiKey, filename)

            val photoUrl = client.getOsintPhotoUrl(filename)
            imageView.setOnClickListener {
                FullscreenImageDialog.newInstance(photoUrl, apiKey)
                    .show(parentFragmentManager, "fullscreen_photo")
            }

            row.addView(imageView)
        }

        scrollView.addView(row)
        return scrollView
    }

    // === Phone Intel ===

    private fun buildPhoneIntelCard(phoneData: JSONObject): MaterialCardView {
        val ctx = requireContext()
        val card = createCard()
        val content = createCardContent()

        content.addView(buildCardHeader("Phone Lookup"))

        val phone = phoneData.optString("phone", "")
        val operator = phoneData.optString("operator", "")
        val country = phoneData.optString("country", "")

        if (phone.isNotEmpty()) {
            val phoneRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            phoneRow.addView(TextView(ctx).apply {
                text = phone
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                textSize = 15f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            val suffix = listOf(operator, country).filter { it.isNotEmpty() }.joinToString(", ")
            if (suffix.isNotEmpty()) {
                phoneRow.addView(TextView(ctx).apply {
                    text = "  $suffix"
                    setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
                    textSize = 13f
                })
            }
            content.addView(phoneRow)
        }

        val fullName = phoneData.optString("fullName", "").ifEmpty { phoneData.optString("name", "") }
        val dob = phoneData.optString("dob", "").ifEmpty { phoneData.optString("birthday", "") }
        if (fullName.isNotEmpty() || dob.isNotEmpty()) {
            val nameText = listOf(fullName, dob).filter { it.isNotEmpty() }.joinToString(", ")
            content.addView(TextView(ctx).apply {
                text = nameText
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dpToPx(6), 0, 0)
            })
        }

        val phonebookNames = phoneData.optJSONArray("phonebookNames")
            ?: phoneData.optJSONArray("names")
        if (phonebookNames != null && phonebookNames.length() > 0) {
            val names = (0 until phonebookNames.length())
                .mapNotNull { phonebookNames.optString(it, "").ifEmpty { null } }
            if (names.isNotEmpty()) {
                content.addView(TextView(ctx).apply {
                    text = names.joinToString(", ")
                    setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
                    textSize = 13f
                    setTypeface(null, Typeface.ITALIC)
                    setPadding(0, dpToPx(4), 0, 0)
                })
            }
        }

        val socialLinks = mutableListOf<Pair<String, String>>()
        phoneData.optString("vkUrl", "").ifEmpty {
            val vkObj = phoneData.optJSONObject("vk")
            vkObj?.optString("url", "") ?: phoneData.optString("vk", "")
        }.let { if (it.isNotEmpty()) socialLinks.add("VK" to it) }
        phoneData.optString("telegramUrl", "").ifEmpty { phoneData.optString("telegram", "") }
            .let { if (it.isNotEmpty()) socialLinks.add("Telegram" to it) }
        phoneData.optString("whatsappUrl", "").ifEmpty { phoneData.optString("whatsapp", "") }
            .let { if (it.isNotEmpty()) socialLinks.add("WhatsApp" to it) }
        phoneData.optString("okUrl", "").ifEmpty { phoneData.optString("ok", "") }
            .let { if (it.isNotEmpty()) socialLinks.add("OK.ru" to it) }

        if (socialLinks.isNotEmpty()) {
            val linksRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpToPx(8), 0, 0)
            }
            socialLinks.forEach { (label, url) ->
                linksRow.addView(buildChipLink(label, url))
            }
            content.addView(linksRow)
        }

        val emails = phoneData.optJSONArray("emails")
        if (emails != null && emails.length() > 0) {
            val emailList = (0 until emails.length())
                .mapNotNull { emails.optString(it, "").ifEmpty { null } }
            if (emailList.isNotEmpty()) {
                content.addView(TextView(ctx).apply {
                    text = emailList.joinToString("\n")
                    setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, dpToPx(6), 0, 0)
                })
            }
        }

        val reportFile = phoneData.optString("localFile", "").ifEmpty { phoneData.optString("report", "") }
        if (reportFile.isNotEmpty()) {
            content.addView(buildClickableLink("View Report", reportFile))
        }

        card.addView(content)
        return card
    }

    // === Identifiers ===

    private fun buildIdentifiersCard(searchKeys: JSONArray, infoFound: JSONObject?): MaterialCardView {
        val card = createCard()
        val content = createCardContent()
        val ctx = requireContext()

        content.addView(buildCardHeader("Identifiers"))

        // Collect values from search4faces_vk to exclude (shown in VK tab)
        val vkValues = mutableSetOf<String>()
        val vkPersons = infoFound?.optJSONObject("search4faces_vk")?.optJSONArray("persons")
        if (vkPersons != null) {
            for (i in 0 until vkPersons.length()) {
                val p = vkPersons.optJSONObject(i) ?: continue
                p.optString("fullName", "").takeIf { it.isNotBlank() }?.let { vkValues.add(it) }
                val vkObj = p.optJSONObject("vk")
                vkObj?.optString("url", "")?.takeIf { it.isNotBlank() }?.let { vkValues.add(it) }
            }
        }

        val grouped = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until searchKeys.length()) {
            val key = searchKeys.optJSONObject(i) ?: continue
            val type = key.optString("type", "unknown")
            val value = key.optString("value", "")
            if (value.isNotEmpty() && value !in vkValues) {
                grouped.getOrPut(type) { mutableListOf() }.add(value)
            }
        }

        if (grouped.isEmpty()) return card.apply { visibility = View.GONE }

        grouped.forEach { (type, values) ->
            content.addView(TextView(ctx).apply {
                text = formatTypeName(type)
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dpToPx(6), 0, dpToPx(2))
            })

            content.addView(TextView(ctx).apply {
                text = values.joinToString("\n")
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }

        card.addView(content)
        return card
    }

    // === Other Sources (collapsible) ===

    private fun buildOtherSourceCard(source: String, data: JSONObject): MaterialCardView {
        val ctx = requireContext()
        val card = createCard()
        val content = createCardContent()

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        headerRow.addView(TextView(ctx).apply {
            text = formatSourceName(source)
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val collapseIndicator = TextView(ctx).apply {
            text = "v"
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
            textSize = 14f
        }
        headerRow.addView(collapseIndicator)

        content.addView(headerRow)

        val dataContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.get(key)
            val valueStr = when (value) {
                is String -> value
                is JSONArray -> {
                    (0 until value.length())
                        .mapNotNull { value.optString(it, "").ifEmpty { null } }
                        .joinToString(", ")
                }
                is JSONObject -> {
                    val parts = mutableListOf<String>()
                    val nk = value.keys()
                    while (nk.hasNext()) {
                        val k = nk.next()
                        val v = value.opt(k)
                        val vs = when (v) {
                            is JSONArray -> (0 until v.length()).mapNotNull {
                                v.optString(it, "").ifEmpty { null }
                            }.joinToString(", ")
                            is String -> v
                            else -> v?.toString() ?: ""
                        }
                        if (vs.isNotEmpty()) parts.add("${formatFieldName(k)}: $vs")
                    }
                    parts.joinToString("\n")
                }
                else -> value?.toString() ?: ""
            }

            if (valueStr.isNotEmpty() && valueStr != "null") {
                dataContainer.addView(buildDataRow(formatFieldName(key), valueStr))
            }
        }

        content.addView(dataContainer)

        headerRow.setOnClickListener {
            if (dataContainer.visibility == View.VISIBLE) {
                dataContainer.visibility = View.GONE
                collapseIndicator.text = "v"
            } else {
                dataContainer.visibility = View.VISIBLE
                collapseIndicator.text = "^"
            }
        }

        card.addView(content)
        return card
    }

    // === Shared UI Builders ===

    internal fun createCard(): MaterialCardView {
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

    internal fun createCardContent(): LinearLayout {
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

    private fun buildCardHeader(title: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(8))
        }
    }

    private fun buildDataRow(label: String, value: String): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, 0)

            addView(TextView(ctx).apply {
                text = label
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_gray))
                textSize = 12f
            })

            addView(TextView(ctx).apply {
                text = value
                setTextColor(ContextCompat.getColor(ctx, R.color.gbx_fg))
                textSize = 14f
                setTextIsSelectable(true)
            })
        }
    }

    internal fun buildClickableLink(label: String, url: String): TextView {
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

    private fun buildChipLink(label: String, url: String): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.gbx_bg))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.gbx_orange))
                cornerRadius = dpToPx(10).toFloat()
            }
            setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpToPx(6) }
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Failed to open URL: ${e.message}")
                }
            }
        }
    }

    // === Formatting ===

    private fun formatSourceName(source: String): String {
        return source.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun formatFieldName(field: String): String {
        return field.replaceFirstChar { it.uppercase() }.replace("_", " ")
    }

    private fun formatTypeName(type: String): String {
        return when (type) {
            "phone" -> "Phones"
            "email", "emails" -> "Emails"
            "name" -> "Names"
            "vk_url" -> "VK URLs"
            "telegram_id" -> "Telegram IDs"
            else -> formatFieldName(type)
        }
    }

    internal fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
