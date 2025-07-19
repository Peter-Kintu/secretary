package com.example.secretary

import android.app.Application
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Instantiate a FlutterEngine.
        val flutterEngine = FlutterEngine(this)

        // Configure an initial route.
        flutterEngine.navigationChannel.setInitialRoute("/")

        // Start executing Dart code to pre-warm the FlutterEngine.
        flutterEngine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )

        // Cache the FlutterEngine to be used by other FlutterActivity or FlutterFragment.
        FlutterEngineCache
            .getInstance()
            .put("my_cached_engine", flutterEngine) // Use a unique ID for your engine
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up the cached FlutterEngine when the application terminates
        FlutterEngineCache.getInstance().remove("my_cached_engine")
    }
}
