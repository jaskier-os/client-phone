package com.repository.listener.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.repository.listener.R

class AudioRecordingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_recordings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AudioRecordingListFragment())
                .commit()
        }
    }
}
