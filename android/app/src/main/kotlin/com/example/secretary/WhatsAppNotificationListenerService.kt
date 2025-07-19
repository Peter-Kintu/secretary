package com.example.secretary

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import com.example.secretary.LogUtils
import android.app.Notification
import android.os.Bundle
import android.os.Build // Import Build class for SDK_INT check
import android.app.Person // Explicitly import Person
// No longer directly importing Notification.MessagingStyle.Message as we use string keys

class WhatsAppNotificationListenerService : NotificationListenerService() {

    private val TAG = "WhatsAppNLService"
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessedNotificationKey: String? = null // For debouncing duplicate notifications

    companion object {
        private const val CHANNEL = "com.example.secretary/whatsapp_messages"
        private var methodChannel: MethodChannel? = null
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        // Define string constants for MessagingStyle.Message keys for robustness
        private const val MESSAGE_KEY_TEXT = "text"
        private const val MESSAGE_KEY_SENDER = "sender"
        private const val MESSAGE_KEY_SENDER_PERSON = "sender_person"

        fun setupMethodChannel(flutterEngine: FlutterEngine) {
            methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            LogUtils.d("WhatsAppNLService", "MethodChannel for WhatsApp messages setup.")
        }
    }

    override fun onListenerConnected() {
        LogUtils.d(TAG, "Notification Listener Connected!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) {
            LogUtils.d(TAG, "Received null StatusBarNotification.")
            return
        }

        // Only process notifications from WhatsApp or WhatsApp Business
        if (sbn.packageName != WHATSAPP_PACKAGE && sbn.packageName != WHATSAPP_BUSINESS_PACKAGE) {
            LogUtils.v(TAG, "Skipping notification from non-WhatsApp package: ${sbn.packageName}")
            return
        }

        // Debounce duplicate notifications (sometimes multiple identical notifications are posted)
        if (sbn.key == lastProcessedNotificationKey) {
            LogUtils.d(TAG, "Skipping duplicate notification: ${sbn.key}")
            return
        }
        lastProcessedNotificationKey = sbn.key

        val extras = sbn.notification.extras

        // --- Debugging: Dump all extras ---
        LogUtils.d(TAG, "Dumping all notification extras for key: ${sbn.key}")
        for (key in extras.keySet()) {
            LogUtils.d(TAG, "  EXTRA $key: ${extras.get(key)}")
        }
        // --- End Debugging ---

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        val isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)

        LogUtils.d(TAG, "WhatsApp Notification Detected:")
        LogUtils.d(TAG, "  Title: $title")
        LogUtils.d(TAG, "  Text: $text")
        LogUtils.d(TAG, "  SubText: $subText")
        LogUtils.d(TAG, "  BigText: $bigText")
        LogUtils.d(TAG, "  Template: $template")
        LogUtils.d(TAG, "  Is Group Conversation: $isGroupConversation")

        // --- NEW AND IMPROVED FILTERING LOGIC ---
        // Filter out summary notifications (e.g., "X messages from Y chats")
        val nonConversationalKeywords = listOf(
            "new messages", "WhatsApp Web", "missed call", "checking for new messages",
            "group privately in a status", "You have new messages",
            "Voice message", "Photo", "Video", "You created group", "You were added to group",
            "Missed voice call", "Missed video call", "Calling", "Incoming call",
            "Ended call", "Call ended", "Typing...", "recording audio..."
        )

        val isSummaryNotification = (text?.contains("messages from") == true && title?.equals("WhatsApp", ignoreCase = true) == true) ||
                                    (text?.contains("new message from") == true && text?.contains("chat") == true) // ✅ FIX: Added this line for "New message from X chat"


        val isNonConversationalSystemMessage = nonConversationalKeywords.any { text?.contains(it, ignoreCase = true) == true } ||
                                               nonConversationalKeywords.any { title?.contains(it, ignoreCase = true) == true }


        if (isSummaryNotification || isNonConversationalSystemMessage) {
            LogUtils.d(TAG, "Filtered out system/non-conversational WhatsApp notification: Title=$title, Text=$text")
            return
        }

        // --- Extract message content more robustly ---
        var messageContent: String? = null
        var messageSender: String? = null

        // Try to get messages from MessagingStyle (most reliable for conversational content)
        // MessagingStyle was introduced in API 24 (Nougat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null && messages.isNotEmpty()) {
                // Iterate from the end to get the latest message
                for (i in messages.size - 1 downTo 0) {
                    val msgBundle = messages[i] as? Bundle // Cast to Bundle
                    if (msgBundle != null) {
                        val msgText = msgBundle.getCharSequence(MESSAGE_KEY_TEXT)?.toString()
                        var msgSender: String? = null

                        // Handle sender extraction based on API level for Notification.Person
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9.0 (Pie) and above
                            val senderPerson = msgBundle.getParcelable<Person>(MESSAGE_KEY_SENDER_PERSON)
                            msgSender = senderPerson?.name?.toString()
                            LogUtils.d(TAG, "Using android.app.Person for sender extraction (API ${Build.VERSION.SDK_INT}).")
                        } else { // Android 7.0 (Nougat) to Android 8.1 (Oreo)
                            msgSender = msgBundle.getCharSequence(MESSAGE_KEY_SENDER)?.toString()
                            LogUtils.d(TAG, "Using CharSequence for sender extraction (API ${Build.VERSION.SDK_INT}).")
                        }

                        if (!msgText.isNullOrEmpty()) {
                            messageContent = msgText
                            messageSender = msgSender // Use the sender from the message
                            LogUtils.d(TAG, "Extracted message from EXTRA_MESSAGES. Sender: '$messageSender', Content: '$messageContent'")
                            // We found the latest non-empty message, so we can break
                            break
                        }
                    }
                }
            }
        } else {
            // For API levels below N (Nougat), MessagingStyle.Message is not reliably available.
            // Fallback to EXTRA_TEXT and EXTRA_TITLE directly.
            LogUtils.d(TAG, "MessagingStyle not fully supported (API < ${Build.VERSION_CODES.N}). Falling back to EXTRA_TEXT and EXTRA_TITLE.")
        }


        // Fallback to EXTRA_TEXT if MessagingStyle didn't yield a message or not supported
        if (messageContent.isNullOrEmpty()) {
            messageContent = text
            messageSender = title // Fallback to notification title for sender
            LogUtils.d(TAG, "Falling back to EXTRA_TEXT. Sender: '$messageSender', Content: '$messageContent'")
        }

        if (!messageContent.isNullOrEmpty() && !messageSender.isNullOrEmpty()) {
            // --- Clean the sender name for more accurate matching ---
            var cleanedSender = messageSender
            // Remove " (X messages)" pattern (e.g., "Group Name (3 messages)")
            cleanedSender = cleanedSender.replace(Regex("\\s*\\(\\d+\\s*messages\\):?.*"), "").trim()
            // Remove "~Name" pattern (e.g., "Group Name: ~John Doe")
            cleanedSender = cleanedSender.replace(Regex(":\\s*~[^:]+"), "").trim()
            // Remove " (X unread messages)" for general WhatsApp summary notifications
            cleanedSender = cleanedSender.replace(Regex("\\s*\\(\\d+\\s*unread messages\\)"), "").trim()
            // Remove "You: " prefix from self-sent messages in notifications if they appear
            cleanedSender = cleanedSender.replace(Regex("^You:\\s*"), "").trim()
            // Remove trailing emojis or special characters that are not part of the name
            cleanedSender = cleanedSender.replace(Regex("[\\p{So}\\s]+$"), "").trim() // Unicode for symbols
            // Handle cases like "Contact Name (2)" often seen in notifications
            cleanedSender = cleanedSender.replace(Regex("\\s*\\(\\d+\\)$"), "").trim()

            LogUtils.d(TAG, "Incoming WhatsApp Message (Final):")
            LogUtils.d(TAG, "  Sender: $cleanedSender")
            LogUtils.d(TAG, "  Message: $messageContent")
            LogUtils.d(TAG, "Cleaned Sender for Flutter: '$cleanedSender'")

            // Click the notification to open the correct chat with retry
            if (clickNotificationWithRetry(sbn)) {
                // Send the message to Flutter immediately
                methodChannel?.let {
                    it.invokeMethod(
                        "incomingWhatsAppMessage",
                        mapOf("sender" to cleanedSender, "message" to messageContent)
                    )
                    LogUtils.d(TAG, "Sending 'incomingWhatsAppMessage' to Flutter with cleaned sender: $cleanedSender")
                } ?: LogUtils.w(TAG, "MethodChannel is null. Could not send incoming WhatsApp message to Flutter.")
            } else {
                LogUtils.e(TAG, "Not sending message to Flutter: Notification click failed after retries.")
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_notification_click") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
            }
        } else {
            LogUtils.d(TAG, "No valid message content or sender extracted from notification.")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && (sbn.packageName == WHATSAPP_PACKAGE || sbn.packageName == WHATSAPP_BUSINESS_PACKAGE)) {
            LogUtils.d(TAG, "WhatsApp notification removed: ${sbn.key}")
            if (sbn.key == lastProcessedNotificationKey) {
                lastProcessedNotificationKey = null // Clear last processed key if removed
            }
        }
    }

    private fun clickNotificationWithRetry(sbn: StatusBarNotification): Boolean {
        val maxRetries = 3
        val retryDelayMs = 1000L

        for (attempts in 0 until maxRetries) {
            try {
                sbn.notification.contentIntent?.send()
                LogUtils.d(TAG, "Successfully clicked WhatsApp notification to open chat on attempt ${attempts + 1}.")
                return true
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to click WhatsApp notification on attempt ${attempts + 1}: ${e.message}", e)
                if (attempts < maxRetries - 1) {
                    val latch = CountDownLatch(1)
                    handler.postDelayed({ latch.countDown() }, retryDelayMs)
                    try {
                        latch.await()
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        LogUtils.e(TAG, "Notification click retry interrupted: ${ie.message}")
                        return false
                    }
                }
            }
        }
        LogUtils.e(TAG, "Failed to click WhatsApp notification after $maxRetries attempts.")
        return false
    }
}
