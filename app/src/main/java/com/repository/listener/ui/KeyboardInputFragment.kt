package com.repository.listener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import androidx.fragment.app.DialogFragment
import com.repository.listener.R
import com.repository.listener.service.ListenerService

class KeyboardInputFragment : DialogFragment() {

    override fun getTheme(): Int = R.style.FullScreenDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_keyboard_input, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val editText = view.findViewById<EditText>(R.id.editText)
        val btnBack = view.findViewById<ImageButton>(R.id.btnBack)
        val btnApply = view.findViewById<ImageButton>(R.id.btnApply)

        btnBack.setOnClickListener { dismiss() }

        val sendAction = {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                sendKeyboardText(text)
            }
            dismiss()
        }

        btnApply.setOnClickListener { sendAction() }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendAction()
                true
            } else {
                false
            }
        }

        // Auto-show keyboard
        editText.requestFocus()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun sendKeyboardText(text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1 + textBytes.size)
        buf[0] = 0x04
        System.arraycopy(textBytes, 0, buf, 1, textBytes.size)
        ListenerService.keyboardEventListener?.invoke(buf)
    }
}
