package com.almica.shareloc.backgroundcamera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class ServiceLifecycleOwner : LifecycleOwner {
    override val lifecycle: Lifecycle
        field: LifecycleRegistry = LifecycleRegistry(this)

    fun start() {
        lifecycle.currentState = Lifecycle.State.STARTED
    }

    fun stop() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}
