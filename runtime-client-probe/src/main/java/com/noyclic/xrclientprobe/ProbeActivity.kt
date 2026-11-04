package com.noyclic.xrclientprobe

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

class ProbeActivity : Activity() {
    internal var serviceReportText: String = ""
        private set

    internal var loaderReportText: String = ""
        private set

    internal var combinedReportText: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serviceReportText = ExternalRuntimeClientProbe.inspect(this).toDisplayText()
        loaderReportText = OpenXrLoaderProbe.inspect(this)
        combinedReportText = buildString {
            appendLine("=== Android service and RuntimeBroker visibility ===")
            appendLine(serviceReportText)
            appendLine()
            appendLine("=== Official Khronos OpenXR loader ===")
            append(loaderReportText)
        }

        val body = TextView(this).apply {
            text = combinedReportText
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(14, 17, 23))
            setPadding(24, 24, 24, 24)
            gravity = Gravity.TOP
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        })
    }
}
