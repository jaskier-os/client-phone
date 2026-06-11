package com.repository.listener.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.repository.listener.R
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector

class TeleprompterInputDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "TeleprompterInputDialog"
        private const val TEST_TEXT = """Ladies and gentlemen, welcome to this demonstration.
This text tests bilingual speech tracking.

Добро пожаловать на демонстрацию системы телесуфлера.
Эта система следит за тем, что вы говорите, и прокручивает текст.

Now we switch back to English. The system should track your position
regardless of which language you are currently speaking.
Система должна работать плавно при переключении между языками."""
    }

    interface Listener {
        fun onTeleprompterTextReady(text: String, fontSize: Int, lang: String)
    }

    private data class LangOption(val code: String, val label: String) {
        override fun toString() = label
    }

    private val languages = listOf(
        LangOption("auto", "Auto"),
        LangOption("ru", "Russian"),
        LangOption("en", "English"),
        LangOption("de", "German"),
        LangOption("fr", "French"),
        LangOption("es", "Spanish"),
        LangOption("zh", "Chinese"),
        LangOption("ja", "Japanese"),
        LangOption("ko", "Korean"),
        LangOption("pt", "Portuguese"),
        LangOption("it", "Italian")
    )

    private var listener: Listener? = null
    private lateinit var editText: EditText
    private lateinit var sliderFontSize: Slider
    private lateinit var labelFontSize: TextView
    private lateinit var spinnerLanguage: Spinner

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) {
                editText.setText(text)
                LogCollector.i(TAG, "File loaded: ${text.length} chars")
            } else {
                Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "File read failed: ${e.message}")
            Toast.makeText(context, "Failed to read file", Toast.LENGTH_SHORT).show()
        }
    }

    fun setListener(l: Listener) {
        listener = l
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_teleprompter_input, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editText = view.findViewById(R.id.editTeleprompterText)
        val btnPaste = view.findViewById<MaterialButton>(R.id.btnPasteText)
        val btnOpenFile = view.findViewById<MaterialButton>(R.id.btnOpenFile)
        val btnStart = view.findViewById<MaterialButton>(R.id.btnStartTeleprompter)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTestTeleprompter)
        sliderFontSize = view.findViewById(R.id.sliderFontSize)
        labelFontSize = view.findViewById(R.id.labelFontSize)
        spinnerLanguage = view.findViewById(R.id.spinnerLanguage)

        // Set up language spinner
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter
        val savedLang = AppConfig.getTeleprompterLang(requireContext())
        val savedIdx = languages.indexOfFirst { it.code == savedLang }.coerceAtLeast(0)
        spinnerLanguage.setSelection(savedIdx)

        // Restore saved font size
        val savedFontSize = AppConfig.getTeleprompterFontSize(requireContext())
            .toFloat().coerceIn(8f, 24f)
        sliderFontSize.value = savedFontSize
        labelFontSize.text = "Font size: ${savedFontSize.toInt()}sp"

        sliderFontSize.addOnChangeListener { _, value, _ ->
            labelFontSize.text = "Font size: ${value.toInt()}sp"
        }

        btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pastedText = clip.getItemAt(0).coerceToText(requireContext()).toString()
                editText.setText(pastedText)
            } else {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenFile.setOnClickListener {
            filePickerLauncher.launch("text/*")
        }

        btnTest.setOnClickListener {
            editText.setText(TEST_TEXT)
            startTeleprompter()
        }

        btnStart.setOnClickListener {
            startTeleprompter()
        }
    }

    private fun startTeleprompter() {
        val text = editText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(context, "Please enter some text", Toast.LENGTH_SHORT).show()
            return
        }
        val fontSize = sliderFontSize.value.toInt()
        val lang = (spinnerLanguage.selectedItem as LangOption).code
        AppConfig.setTeleprompterFontSize(requireContext(), fontSize)
        AppConfig.setTeleprompterLang(requireContext(), lang)
        listener?.onTeleprompterTextReady(text, fontSize, lang)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }
}
