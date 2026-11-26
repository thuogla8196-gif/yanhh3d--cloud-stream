package com.thuongtran.film4k

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Film4KPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Film4KProvider())
    }
}
