package com.noyclic.questphonexr.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Marker service read by the installable Khronos OpenXR RuntimeBroker.
 * OpenXR defines no Binder methods for this discovery service.
 */
class EndXrRuntimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
