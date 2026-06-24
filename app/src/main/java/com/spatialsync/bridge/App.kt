//App.kt
package com.spatialsync.bridge

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
            .onFailure { error, _ ->
                android.util.Log.e("SpatialSync", "DAT init failed: $error")
            }
    }
}