package com.noyclic.xrclientprobe

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = ExternalRuntimeClientProbe.inspect(this).toDisplayText()
        val body = TextView(this).apply {
            text = report
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(14, 17, 23))
            setPadding(24, 24, 24, 24)
            gravity = Gravity.TOP
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        })
    }
}
