package com.repository.listener.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.fragment.app.DialogFragment
import com.repository.listener.util.ImageCacheUtil

class FullscreenImageDialog : DialogFragment() {

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_API_KEY = "api_key"

        fun newInstance(url: String, apiKey: String): FullscreenImageDialog {
            return FullscreenImageDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_API_KEY, apiKey)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val imageView = ImageView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#DD000000"))
            setOnClickListener { dismiss() }
        }

        val url = arguments?.getString(ARG_URL) ?: ""
        val apiKey = arguments?.getString(ARG_API_KEY) ?: ""
        if (url.isNotEmpty()) {
            ImageCacheUtil.loadImage(imageView, url, url, apiKey)
        }

        return imageView
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }
}
