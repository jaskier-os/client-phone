package com.repository.listener.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.repository.listener.R
import com.repository.listener.config.AppConfig

class TranslationConfigDialog : BottomSheetDialogFragment() {

    data class Language(val name: String, val code: String, val nllbCode: String) {
        override fun toString(): String = "$name ($code)"
    }

    data class TranslationConfig(
        val from: Language,
        val to: Language,
        val fontSize: Int,
        val audioSource: String,
        val provider: String = "default"
    )

    interface Listener {
        fun onTranslationConfigReady(config: TranslationConfig)
        // Two-way: requires the auto-detect bidirectional flow. Always Azure +
        // glasses mic; the receiving Activity owns the lifecycle.
        fun onTwoWayTranslationRequested(from: Language, to: Language, fontSize: Int) {}
    }

    companion object {
        private val languages = listOf(
            Language("English", "EN", "eng_Latn"),
            Language("Russian", "RU", "rus_Cyrl"),
            Language("Chinese", "ZH", "zho_Hans"),
            Language("Japanese", "JA", "jpn_Jpan"),
            Language("Korean", "KO", "kor_Hang"),
            Language("French", "FR", "fra_Latn"),
            Language("German", "DE", "deu_Latn"),
            Language("Spanish", "ES", "spa_Latn"),
            Language("Italian", "IT", "ita_Latn"),
            Language("Portuguese", "PT", "por_Latn"),
            Language("Arabic", "AR", "ara_Arab"),
            Language("Hindi", "HI", "hin_Deva"),
            Language("Turkish", "TR", "tur_Latn"),
            Language("Vietnamese", "VI", "vie_Latn"),
            Language("Thai", "TH", "tha_Thai"),
            Language("Indonesian", "ID", "ind_Latn"),
            Language("Dutch", "NL", "nld_Latn"),
            Language("Polish", "PL", "pol_Latn"),
            Language("Ukrainian", "UK", "ukr_Cyrl"),
            Language("Czech", "CS", "ces_Latn"),
            Language("Swedish", "SV", "swe_Latn"),
            Language("Greek", "EL", "ell_Grek"),
            Language("Hebrew", "HE", "heb_Hebr"),
            Language("Romanian", "RO", "ron_Latn"),
            Language("Hungarian", "HU", "hun_Latn")
        )

        private val audioSourceOptions = listOf("Glasses Mic", "System Audio")
        private val audioSourceValues = listOf("glasses", "system")
        private val providerOptions = listOf("Default", "Azure")
        private val providerValues = listOf("default", "azure")
        private const val ARG_SESSION_ACTIVE = "session_active"
    }

    private var listener: Listener? = null
    private var selectedFrom: Language? = null
    private var selectedTo: Language? = null

    fun setListener(l: Listener) {
        listener = l
    }

    fun setSessionActive(active: Boolean) {
        arguments = (arguments ?: Bundle()).apply { putBoolean(ARG_SESSION_ACTIVE, active) }
    }

    private val sessionActive: Boolean
        get() = arguments?.getBoolean(ARG_SESSION_ACTIVE, false) ?: false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_translation_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val dropdownFrom = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownFromLanguage)
        val dropdownTo = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownToLanguage)
        val slider = view.findViewById<Slider>(R.id.sliderTranslationFontSize)
        val labelFontSize = view.findViewById<TextView>(R.id.labelFontSize)
        val dropdownAudio = view.findViewById<AutoCompleteTextView>(R.id.dropdownAudioSource)
        val dropdownProvider = view.findViewById<AutoCompleteTextView>(R.id.dropdownProvider)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStartTranslation)
        val btnTwoWay = view.findViewById<MaterialButton>(R.id.btnTwoWayTranslation)
        val btnSwap = view.findViewById<MaterialButton>(R.id.btnSwapLanguages)

        // Language dropdowns
        val languageStrings = languages.map { it.toString() }
        dropdownFrom.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, languageStrings))
        dropdownTo.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, languageStrings))

        // Restore persisted selections
        val savedFrom = AppConfig.getTranslationFromLanguage(ctx)
        if (savedFrom.isNotEmpty()) {
            val lang = languages.find { it.toString() == savedFrom }
            if (lang != null) {
                selectedFrom = lang
                dropdownFrom.setText(lang.toString(), false)
            }
        }
        val savedTo = AppConfig.getTranslationToLanguage(ctx)
        if (savedTo.isNotEmpty()) {
            val lang = languages.find { it.toString() == savedTo }
            if (lang != null) {
                selectedTo = lang
                dropdownTo.setText(lang.toString(), false)
            }
        }

        dropdownFrom.setOnItemClickListener { parent, _, position, _ ->
            val text = parent.getItemAtPosition(position) as String
            selectedFrom = languages.find { it.toString() == text }
            AppConfig.setTranslationFromLanguage(ctx, text)
            hideKeyboard(dropdownFrom)
        }
        dropdownTo.setOnItemClickListener { parent, _, position, _ ->
            val text = parent.getItemAtPosition(position) as String
            selectedTo = languages.find { it.toString() == text }
            AppConfig.setTranslationToLanguage(ctx, text)
            hideKeyboard(dropdownTo)
        }

        // Font size
        val savedFontSize = AppConfig.getTranslationFontSize(ctx).toFloat().coerceIn(8f, 24f)
        slider.value = savedFontSize
        labelFontSize.text = "Font size: ${savedFontSize.toInt()}sp"
        slider.addOnChangeListener { _, value, _ ->
            labelFontSize.text = "Font size: ${value.toInt()}sp"
        }

        // Provider
        dropdownProvider.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, providerOptions))
        val savedProvider = AppConfig.getTranslationProvider(ctx)
        val savedProviderIndex = providerValues.indexOf(savedProvider).coerceAtLeast(0)
        dropdownProvider.setText(providerOptions[savedProviderIndex], false)
        dropdownProvider.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setTranslationProvider(ctx, providerValues[position])
        }

        // Audio source
        dropdownAudio.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, audioSourceOptions))
        val savedAudioSource = AppConfig.getTranslationAudioSource(ctx)
        val savedAudioIndex = audioSourceValues.indexOf(savedAudioSource).coerceAtLeast(0)
        dropdownAudio.setText(audioSourceOptions[savedAudioIndex], false)
        dropdownAudio.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setTranslationAudioSource(ctx, audioSourceValues[position])
        }

        // When a session is active, change button label to indicate restart behavior.
        if (sessionActive) {
            btnStart.text = "Apply & Restart"
        }

        // Swap source <-> target. Updates dropdowns + persisted prefs in one go.
        btnSwap.setOnClickListener {
            val newFrom = selectedTo
            val newTo = selectedFrom
            selectedFrom = newFrom
            selectedTo = newTo
            dropdownFrom.setText(newFrom?.toString() ?: "", false)
            dropdownTo.setText(newTo?.toString() ?: "", false)
            if (newFrom != null) AppConfig.setTranslationFromLanguage(ctx, newFrom.toString())
            if (newTo != null) AppConfig.setTranslationToLanguage(ctx, newTo.toString())
        }

        // Start button
        btnStart.setOnClickListener {
            val from = selectedFrom
            val to = selectedTo
            if (from == null || to == null) {
                Toast.makeText(ctx, "Select both languages", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (from == to) {
                Toast.makeText(ctx, "Languages must be different", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fontSize = slider.value.toInt()
            AppConfig.setTranslationFontSize(ctx, fontSize)
            val audioSource = AppConfig.getTranslationAudioSource(ctx)
            val provider = AppConfig.getTranslationProvider(ctx)
            listener?.onTranslationConfigReady(TranslationConfig(from, to, fontSize, audioSource, provider))
            dismiss()
        }

        btnTwoWay.setOnClickListener {
            val from = selectedFrom
            val to = selectedTo
            if (from == null || to == null) {
                Toast.makeText(ctx, "Select both languages", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (from == to) {
                Toast.makeText(ctx, "Languages must be different", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fontSize = slider.value.toInt()
            AppConfig.setTranslationFontSize(ctx, fontSize)
            listener?.onTwoWayTranslationRequested(from, to, fontSize)
            dismiss()
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }
}
