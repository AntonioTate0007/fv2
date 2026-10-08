package com.jarvis.glasses

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables

class JarvisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initWearables()
    }

    /** The Meta SDK must only be initialized once Bluetooth permission is granted. */
    fun initWearables(): Boolean {
        if (wearablesReady) return true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        Wearables.initialize(this)
            .onSuccess { wearablesReady = true }
            .onFailure { error, _ -> Log.w("JarvisApp", "Wearables.initialize failed: ${error.description}") }
        return wearablesReady
    }

    companion object {
        @Volatile
        var wearablesReady = false
            private set
    }
}
