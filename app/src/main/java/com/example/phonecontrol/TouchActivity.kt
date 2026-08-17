package com.example.phonecontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TouchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_touch)
        findViewById<MaterialToolbar>(R.id.toolbarTouch).setNavigationOnClickListener { finish() }
    }
}
