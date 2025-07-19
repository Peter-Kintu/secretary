package com.example.secretary

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.util.Log
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

class MainActivity: FlutterActivity() {
    private val CALL_CHANNEL = "com.example.secretary/call_state"
    private lateinit var callMethodChannel: MethodChannel
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Setup MethodChannel for Call Detection
        callMethodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CALL_CHANNEL)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                super.onCallStateChanged(state, incomingNumber)
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d("MainActivity", "Incoming call: $incomingNumber")
                        callMethodChannel.invokeMethod("incomingCall", incomingNumber)
                    }
                    // Add other states if needed (e.g., CALL_STATE_OFFHOOK, CALL_STATE_IDLE)
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d("MainActivity", "CallStateListener registered.")


        // Setup MethodChannel for WhatsApp Notification Listener Service
        WhatsAppNotificationListenerService.setupMethodChannel(flutterEngine)
        Log.d("MainActivity", "WhatsAppNotificationListenerService MethodChannel setup.")

        // Setup MethodChannel for WhatsApp Reply Accessibility Service
        WhatsAppReplyAccessibilityService.setupMethodChannel(flutterEngine)
        Log.d("MainActivity", "WhatsAppReplyAccessibilityService MethodChannel setup.")

        // Handle method calls from Flutter for Accessibility Service
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.example.secretary/accessibility_service").setMethodCallHandler { call, result ->
            if (call.method == "sendWhatsAppReply") {
                val sender = call.argument<String>("sender")
                val message = call.argument<String>("message")
                if (sender != null && message != null) {
                    // FIX: Use the more robust check for Accessibility Service enabled
                    if (isAccessibilityServiceEnabled(this, WhatsAppReplyAccessibilityService::class.java.name)) {
                        Log.d("MainActivity", "Accessibility Service is enabled. Attempting to send reply via instance.")
                        WhatsAppReplyAccessibilityService.instance?.sendWhatsAppReply(sender, message)
                        result.success(true)
                    } else {
                        Log.e("MainActivity", "Accessibility Service not enabled. Cannot send WhatsApp reply. Prompting user.")
                        result.error("ACCESSIBILITY_SERVICE_DISABLED", "Accessibility Service is not enabled.", null)
                        // Prompt user to enable it
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                } else {
                    result.error("INVALID_ARGUMENTS", "Sender or message is null.", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the PhoneStateListener to prevent leaks
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        Log.d("MainActivity", "CallStateListener unregistered.")
    }

    // FIX: Updated helper to check if Accessibility Service is enabled using Settings.Secure
    private fun isAccessibilityServiceEnabled(context: Context, accessibilityServiceClassName: String): Boolean {
        val expectedComponentName = "${context.packageName}/$accessibilityServiceClassName"
        val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        Log.d("MainActivity", "Checking accessibility service: Expected=$expectedComponentName, EnabledServices=$enabledServicesSetting")

        return enabledServicesSetting?.contains(expectedComponentName) == true
    }
}
