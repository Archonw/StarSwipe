package io.github.archonw.starswipe

import android.app.Application

class StarSwipeApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.enqueueAllFlushes() // liegengebliebene Bewertungen nachholen
    }
}
