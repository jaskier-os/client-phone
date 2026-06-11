package com.repository.listener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.repository.listener.R
import com.repository.listener.config.AppConfig

class SystemPromptDialog : BottomSheetDialogFragment() {

    private lateinit var editText: EditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_system_prompt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editText = view.findViewById(R.id.editSystemPrompt)
        val btnClear = view.findViewById<MaterialButton>(R.id.btnClearSystemPrompt)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveSystemPrompt)

        // Load current value
        val current = AppConfig.getUserSystemPrompt(requireContext())
        if (current.isNotBlank()) {
            editText.setText(current)
        }

        btnClear.setOnClickListener {
            editText.text.clear()
        }

        btnSave.setOnClickListener {
            val prompt = editText.text.toString().trim()
            AppConfig.setUserSystemPrompt(requireContext(), prompt)
            Toast.makeText(context, if (prompt.isEmpty()) "System prompt cleared" else "System prompt saved", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }
}
