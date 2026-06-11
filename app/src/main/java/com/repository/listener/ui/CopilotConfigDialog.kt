package com.repository.listener.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.repository.listener.R
import com.repository.listener.config.AppConfig

/**
 * Config for the Copilot feature. The wearer and interlocutor are each
 * transcribed in a user-selected language (two parallel Azure recognizers), and
 * the interlocutor audio can come from the glasses external mic or system audio
 * (for when the wearer is in a phone call / media playback).
 */
class CopilotConfigDialog : BottomSheetDialogFragment() {

    data class Language(val name: String, val bcp47: String) {
        override fun toString(): String = "$name ($bcp47)"
    }

    data class CopilotConfig(
        val wearerLang: Language,
        val interlocutorLang: Language,
        val interlocutorSource: String,
        val model: String
    )

    interface Listener {
        fun onCopilotConfigReady(config: CopilotConfig)
    }

    companion object {
        private val languages = listOf(
            Language("English", "en-US"),
            Language("Russian", "ru-RU"),
            Language("Chinese", "zh-CN"),
            Language("Japanese", "ja-JP"),
            Language("Korean", "ko-KR"),
            Language("French", "fr-FR"),
            Language("German", "de-DE"),
            Language("Spanish", "es-ES"),
            Language("Italian", "it-IT"),
            Language("Portuguese", "pt-PT"),
            Language("Arabic", "ar-SA"),
            Language("Hindi", "hi-IN"),
            Language("Turkish", "tr-TR"),
            Language("Vietnamese", "vi-VN"),
            Language("Thai", "th-TH"),
            Language("Indonesian", "id-ID"),
            Language("Dutch", "nl-NL"),
            Language("Polish", "pl-PL"),
            Language("Ukrainian", "uk-UA"),
            Language("Czech", "cs-CZ"),
            Language("Swedish", "sv-SE"),
            Language("Greek", "el-GR"),
            Language("Hebrew", "he-IL"),
            Language("Romanian", "ro-RO"),
            Language("Hungarian", "hu-HU")
        )

        private val sourceOptions = listOf("Glasses Mic", "System Audio")
        private val sourceValues = listOf("glasses", "system")

        // Model picker. "Haiku (fastest)" is the default -- fastest tier and the
        // only model the backend does not wrap in adaptive thinking.
        private val modelOptions = listOf("Haiku (fastest)", "Sonnet (smarter)", "Opus (deepest)")
        private val modelValues = listOf("haiku", "sonnet", "opus")
    }

    private var listener: Listener? = null
    private var selectedWearer: Language? = null
    private var selectedInterlocutor: Language? = null

    fun setListener(l: Listener) {
        listener = l
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_copilot_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val dropdownWearer = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownWearerLanguage)
        val dropdownInterlocutor = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdownInterlocutorLanguage)
        val dropdownSource = view.findViewById<AutoCompleteTextView>(R.id.dropdownInterlocutorSource)
        val dropdownModel = view.findViewById<AutoCompleteTextView>(R.id.dropdownAssistantModel)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStartAssistant)

        val languageStrings = languages.map { it.toString() }
        dropdownWearer.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, languageStrings))
        dropdownInterlocutor.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, languageStrings))

        // Restore persisted selections
        val savedWearer = AppConfig.getAssistantWearerLanguage(ctx)
        languages.find { it.toString() == savedWearer }?.let {
            selectedWearer = it
            dropdownWearer.setText(it.toString(), false)
        }
        val savedInterlocutor = AppConfig.getAssistantInterlocutorLanguage(ctx)
        languages.find { it.toString() == savedInterlocutor }?.let {
            selectedInterlocutor = it
            dropdownInterlocutor.setText(it.toString(), false)
        }

        dropdownWearer.setOnItemClickListener { parent, _, position, _ ->
            val text = parent.getItemAtPosition(position) as String
            selectedWearer = languages.find { it.toString() == text }
            AppConfig.setAssistantWearerLanguage(ctx, text)
            hideKeyboard(dropdownWearer)
        }
        dropdownInterlocutor.setOnItemClickListener { parent, _, position, _ ->
            val text = parent.getItemAtPosition(position) as String
            selectedInterlocutor = languages.find { it.toString() == text }
            AppConfig.setAssistantInterlocutorLanguage(ctx, text)
            hideKeyboard(dropdownInterlocutor)
        }

        // Interlocutor audio source
        dropdownSource.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, sourceOptions))
        val savedSource = AppConfig.getAssistantInterlocutorSource(ctx)
        val savedSourceIndex = sourceValues.indexOf(savedSource).coerceAtLeast(0)
        dropdownSource.setText(sourceOptions[savedSourceIndex], false)
        dropdownSource.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setAssistantInterlocutorSource(ctx, sourceValues[position])
        }

        // Model: restore cached selection, default to the first option (haiku).
        dropdownModel.setAdapter(NoFilterAdapter(ctx, android.R.layout.simple_dropdown_item_1line, modelOptions))
        val savedModel = AppConfig.getAssistantModel(ctx)
        val savedModelIndex = modelValues.indexOf(savedModel).let { if (it < 0) 0 else it }
        dropdownModel.setText(modelOptions[savedModelIndex], false)
        dropdownModel.setOnItemClickListener { _, _, position, _ ->
            AppConfig.setAssistantModel(ctx, modelValues[position])
        }

        btnStart.setOnClickListener {
            val wearer = selectedWearer
            val interlocutor = selectedInterlocutor
            if (wearer == null || interlocutor == null) {
                Toast.makeText(ctx, "Select both languages", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val source = AppConfig.getAssistantInterlocutorSource(ctx)
            val model = AppConfig.getAssistantModel(ctx)
            listener?.onCopilotConfigReady(CopilotConfig(wearer, interlocutor, source, model))
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
