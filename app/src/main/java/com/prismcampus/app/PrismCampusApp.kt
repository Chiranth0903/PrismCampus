package com.prismcampus.app

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class PrismCampusApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure OSMDroid for offline caching without crash if storage is limited
        try {
            val osmConfig = Configuration.getInstance()
            osmConfig.userAgentValue = packageName
            val basePath = File(cacheDir, "osmdroid")
            if (!basePath.exists()) {
                basePath.mkdirs()
            }
            osmConfig.osmdroidBasePath = basePath
            osmConfig.osmdroidTileCache = File(basePath, "tiles")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
