package com.example.secretary // Ensure this matches your package name!

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

class CallStateReceiver : BroadcastReceiver() {

    // This channel name MUST exactly match the one you'll use in your Flutter Dart code.
    companion object {
        private const val CHANNEL = "com.example.secretary/call_state"
        private var flutterEngine: FlutterEngine? = null
        private var channel: MethodChannel? = null
        private var isFlutterEngineInitialized = false

        // This method can be called from MainActivity to ensure the engine is ready
        // if the app is already running.
        @JvmStatic
        fun setupMethodChannel(engine: FlutterEngine) {
            if (!isFlutterEngineInitialized) {
                flutterEngine = engine
                channel = MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
                isFlutterEngineInitialized = true
                Log.d("CallStateReceiver", "FlutterEngine and MethodChannel setup from MainActivity.")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Check if the intent action is related to phone state changes
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d("CallStateReceiver", "Phone State: $state, Incoming Number: $incomingNumber")

            // We are interested when the phone is ringing
            if (TelephonyManager.EXTRA_STATE_RINGING == state) {
                if (!incomingNumber.isNullOrEmpty()) {
                    Log.d("CallStateReceiver", "Incoming call from: $incomingNumber")

                    // Initialize FlutterEngine if it hasn't been already.
                    // This allows the BroadcastReceiver to send data to Flutter
                    // even if the app is not currently in the foreground.
                    if (!isFlutterEngineInitialized) {
                        Log.d("CallStateReceiver", "Initializing FlutterEngine from BroadcastReceiver...")
                        // Use the application context to avoid memory leaks
                        flutterEngine = FlutterEngine(context.applicationContext)
                        // Start executing Dart code immediately
                        flutterEngine?.dartExecutor?.executeDartEntrypoint(
                            DartExecutor.DartEntrypoint.createDefault()
                        )
                        // Set up the MethodChannel using the new FlutterEngine
                        channel = MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, CHANNEL)
                        isFlutterEngineInitialized = true
                        Log.d("CallStateReceiver", "FlutterEngine Initialized from BroadcastReceiver.")
                    } else {
                        Log.d("CallStateReceiver", "FlutterEngine already initialized.")
                    }

                    // Invoke the method on the Flutter side if the channel is ready
                    if (channel != null) {
                        Log.d("CallStateReceiver", "Sending 'incomingCall' to Flutter with number: $incomingNumber")
                        channel?.invokeMethod("incomingCall", incomingNumber)
                    } else {
                        Log.e("CallStateReceiver", "MethodChannel is null. Cannot send data to Flutter.")
                    }
                }
            }
        }
    }
}