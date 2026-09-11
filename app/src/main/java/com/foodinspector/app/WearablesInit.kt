package com.foodinspector.app

import android.content.Context
import com.meta.wearable.dat.core.Wearables

/**
 * Lazy, idempotent DAT initialization adapted from the supplied Meta
 * CameraAccessAndroid sample. Phone/backend-only paths do not initialize DAT.
 */
object WearablesInit {
    @Volatile
    private var initialized = false

    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Wearables.initialize(context.applicationContext)
                .onFailure { error -> throw IllegalStateException("DAT init failed: $error") }
            initialized = true
        }
    }
}
