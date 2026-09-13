package io.farewell.toolbox

import android.app.Application
import io.farewell.toolbox.core.AutoRefresh

class FarewellApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (AutoRefresh.isEnabled(this)) {
            AutoRefresh.schedule(this)
        }
    }
}
