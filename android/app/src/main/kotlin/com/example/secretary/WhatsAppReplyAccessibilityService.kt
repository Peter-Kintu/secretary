package com.example.secretary // Make sure this matches your package name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch // For non-blocking sleep
import com.example.secretary.LogUtils // Import the new LogUtils file

// Coroutine imports (still needed for serviceScope.launch)
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhatsAppReplyAccessibilityService : AccessibilityService() {

    private val TAG = "WhatsAppReplyService"
    private val handler = Handler(Looper.getMainLooper()) // Handler for posting delayed actions
    private var isReplyInProgress = false // Flag to prevent duplicate replies
    private val showToasts = true // Control whether toasts are shown (e.g., set to false for production)

    // Define a CoroutineScope for the service lifecycle
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    // Static instance for easy access from Flutter's MethodChannel handler
    companion object {
        var instance: WhatsAppReplyAccessibilityService? = null
        // Define a MethodChannel to communicate back to Flutter if needed (e.g., for status updates)
        private const val CHANNEL = "com.example.secretary/accessibility_service"
        private var methodChannel: MethodChannel? = null

        // This method can be called from MainActivity to set up the MethodChannel
        fun setupMethodChannel(flutterEngine: FlutterEngine) {
            methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            LogUtils.d("WhatsAppReplyService", "MethodChannel for Accessibility Service setup.")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this // Set instance when service connects
        LogUtils.d(TAG, "Accessibility Service Connected!")
        if (showToasts) {
            Toast.makeText(this, "AI Assistant Accessibility Service Connected", Toast.LENGTH_SHORT).show()
        }

        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

        info.packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b") // Target both WhatsApp and WhatsApp Business

        this.serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "com.whatsapp" && event.packageName != "com.whatsapp.w4b") {
            return // Only process events from WhatsApp
        }

        LogUtils.v(TAG, "Event: ${event.eventType}, Class: ${event.className}, Package: ${event.packageName}, Text: ${event.text}")

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (className?.contains("Dialog") == true || className?.contains("Popup") == true) {
                LogUtils.d(TAG, "Skipping event due to dialog/popup: $className")
                return // Skip processing if the UI is in a dialog or popup state
            }
        }
        // No direct send button clicking here. Reply is triggered by Flutter via sendWhatsAppReply.
    }

    override fun onInterrupt() {
        LogUtils.w(TAG, "Accessibility Service Interrupted!")
        if (showToasts) {
            Toast.makeText(this, "AI Assistant Accessibility Service Interrupted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null // Clear instance when service unbinds
        LogUtils.d(TAG, "Accessibility Service Unbound.")
        if (showToasts) {
            Toast.makeText(this, "AI Assistant Accessibility Service Unbound", Toast.LENGTH_SHORT).show()
        }
        return super.onUnbind(intent)
    }

    fun sendWhatsAppReply(sender: String, message: String) {
        if (isReplyInProgress) {
            LogUtils.w(TAG, "Reply already in progress. Skipping new request for sender: '$sender'.")
            methodChannel?.invokeMethod("whatsappReplyStatus", "skipped_reply_in_progress") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
            return
        }

        isReplyInProgress = true
        LogUtils.d(TAG, "Attempting to send WhatsApp reply to '$sender': '$message'")
        methodChannel?.invokeMethod("whatsappReplyStatus", "attempting_reply_to_sender") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")

        // Add Toast to confirm sendWhatsAppReply is triggered
        if (showToasts) {
            Toast.makeText(this, "AI Assistant: Triggering reply to $sender", Toast.LENGTH_SHORT).show()
        }

        serviceScope.launch {
            waitForUiToStabilize { // This is a suspend function, so it needs to be in a coroutine
                performReplyAction(sender, message) // This will now contain blocking calls
                isReplyInProgress = false // Reset flag after action is performed
            }
        }
    }

    /**
     * Waits for the UI to stabilize by repeatedly checking if rootInActiveWindow is not null
     * and has children.
     * @param callback The action to perform once the UI is stable.
     */
    private suspend fun waitForUiToStabilize(callback: suspend () -> Unit) {
        val maxTries = 10
        var tries = 0
        val checkIntervalMs = 500L // Check every 0.5 seconds

        while (tries < maxTries) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && rootNode.childCount > 0) {
                LogUtils.d(TAG, "UI stabilized after ${tries * checkIntervalMs}ms.")
                callback()
                rootNode.recycle() // Recycle the node after use
                return
            } else {
                LogUtils.d(TAG, "UI not yet stable. Retrying in ${checkIntervalMs}ms. Attempt ${tries + 1}/$maxTries.")
                tries++
                kotlinx.coroutines.delay(checkIntervalMs) // Using coroutines delay here as this is a suspend function
            }
        }
        LogUtils.e(TAG, "UI never stabilized after $maxTries attempts. Giving up.")
        methodChannel?.invokeMethod("whatsappReplyStatus", "failed_ui_not_ready") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
    }

    // FIX: Removed 'suspend' from performReplyAction as it will now use Thread.sleep()
    private fun performReplyAction(sender: String, message: String) {
        val maxRetries = 3
        val retryDelayMs = 500L // 0.5 second delay between retries

        // Helper for retry logic with blocking delay
        // FIX: Removed 'suspend' from retryOperation and renamed it
        fun retryOperationBlocking(maxRetries: Int, delayMs: Long, operation: () -> Boolean): Boolean {
            var attempts = 0
            while (attempts < maxRetries) {
                if (operation()) {
                    LogUtils.d(TAG, "Operation successful on attempt ${attempts + 1}.")
                    return true
                }
                attempts++
                if (attempts < maxRetries) {
                    LogUtils.w(TAG, "Operation failed on attempt ${attempts}. Retrying in ${delayMs}ms...")
                    Thread.sleep(delayMs) // FIX: Using Thread.sleep()
                }
            }
            LogUtils.e(TAG, "Operation failed after $maxRetries attempts.")
            return false
        }

        // Refetch rootNode at the beginning of performReplyAction for the freshest view
        val rootNode = rootInActiveWindow ?: run {
            LogUtils.e(TAG, "Root node is null at time of performReplyAction(). Cannot send reply.")
            methodChannel?.invokeMethod("whatsappReplyStatus", "failed_null_root_during_action") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
            return
        }

        if (LogUtils.logLevel <= Log.DEBUG) {
            LogUtils.d(TAG, "Dumping Accessibility Tree before chat title search:")
            dumpNodeTree(rootNode)
        }

        try {
            // --- VERIFY THE ACTIVE CHAT WINDOW (NOW OPTIONAL/FALLBACK-SAFE) ---
            val chatTitleNode = findChatTitleNode(rootNode)
            if (chatTitleNode == null) {
                LogUtils.w(TAG, "Could not find chat title node. Proceeding with reply anyway.")
                methodChannel?.invokeMethod("whatsappReplyStatus", "warning_chat_title_not_found")
            } else {
                val currentChatTitle = chatTitleNode.text?.toString()
                LogUtils.d(TAG, "Detected current chat title: '$currentChatTitle'. Expected sender: '$sender'")
            }

            // 1. Find the input field
            LogUtils.d(TAG, "Searching for WhatsApp input field...")
            val commonInputIds = listOf(
                "com.whatsapp:id/entry",
                "com.whatsapp:id/et_text_input",
                "com.whatsapp:id/message_et",
                "com.whatsapp:id/text_input"
            )
            val inputTexts = listOf("Type a message", "Message")
            val inputContentDescriptions = listOf("Message", "Type a message")

            var inputField: AccessibilityNodeInfo? = null
            // Try by ID first
            inputField = commonInputIds.firstNotNullOfOrNull { id: String ->
                rootNode.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            }

            // Fallback to text/content description using new helper functions
            if (inputField == null) {
                LogUtils.d(TAG, "Input field not found by ID. Searching by text...")
                inputField = inputTexts.firstNotNullOfOrNull { text: String ->
                    findNodesByText(rootNode, text).firstOrNull()
                }
            }
            if (inputField == null) {
                LogUtils.d(TAG, "Input field not found by text. Searching by content description...")
                inputField = inputContentDescriptions.firstNotNullOfOrNull { desc: String ->
                    findNodesByContentDescription(rootNode, desc).firstOrNull()
                }
            }

            if (inputField == null) {
                LogUtils.e(TAG, "WhatsApp input field not found after trying multiple methods and $maxRetries retries. Dumping tree for analysis.")
                dumpNodeTree(rootNode) // Dump tree if input field not found
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_input_not_found") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                return
            }
            LogUtils.d(TAG, "Found WhatsApp input field. Class: ${inputField.className}, Text: '${inputField.text}', ID: ${inputField.viewIdResourceName}, Editable: ${inputField.isEditable}, Actions: ${inputField.actionList}")

            // Ensure the input field is editable
            if (!inputField.isEditable) {
                LogUtils.e(TAG, "Input field found but is not editable. Cannot send reply. Dumping tree for analysis.")
                dumpNodeTree(rootNode) // Dump tree if input field not editable
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_input_not_editable") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                return
            }

            // 2. Paste the AI's reply into it
            LogUtils.d(TAG, "Attempting to set text: '$message'")
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            var setTextSuccess = inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (setTextSuccess) {
                LogUtils.d(TAG, "Text pasted into input field using ACTION_SET_TEXT successfully.")
            } else {
                LogUtils.w(TAG, "ACTION_SET_TEXT failed. Attempting robust clipboard paste as fallback.")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("whatsapp_reply", message)
                clipboard.setPrimaryClip(clip)

                // Request focus before pasting
                if (!inputField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    LogUtils.e(TAG, "Failed to focus on input field before pasting.")
                    methodChannel?.invokeMethod("whatsappReplyStatus", "failed_focus_for_paste") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                    return
                }
                Thread.sleep(500) // FIX: Using Thread.sleep() // Allow clipboard to settle and focus to take effect

                setTextSuccess = inputField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                LogUtils.d(TAG, "Performed paste action: $setTextSuccess")

                if (setTextSuccess) {
                    LogUtils.d(TAG, "Text pasted into input field using clipboard fallback successfully.")
                } else {
                    LogUtils.e(TAG, "Failed to set text on input field even with clipboard fallback. Dumping tree for analysis.")
                    dumpNodeTree(rootNode) // Dump tree if text setting fails
                    methodChannel?.invokeMethod("whatsappReplyStatus", "failed_set_text_or_paste") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                    return
                }
            }
            Thread.sleep(500) // FIX: Using Thread.sleep() // Delay after setting text before looking for send button

            // 3. Find and click the send button
            LogUtils.d(TAG, "Searching for send button...")
            // Refetch rootNode before searching for send button, in case UI changed
            val currentRootNodeForSend = rootInActiveWindow ?: run {
                LogUtils.e(TAG, "Root node is null when searching for send button. Cannot send reply.")
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_null_root_for_send") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                return
            }

            val sendButtonIds = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp:id/send_button"
            )
            val sendButtonContentDescriptions = listOf("Send")

            var sendButton: AccessibilityNodeInfo? = null
            sendButton = sendButtonIds.firstNotNullOfOrNull { id: String ->
                currentRootNodeForSend.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            }

            if (sendButton == null) {
                LogUtils.d(TAG, "Send button not found by ID. Searching by content description...")
                sendButton = findNodesByContentDescription(currentRootNodeForSend, "Send").firstOrNull()
                if (sendButton != null) {
                    LogUtils.d(TAG, "Found send button by custom content description search: 'Send'")
                }
            }

            if (sendButton == null) {
                LogUtils.d(TAG, "Send button not found by ID or content description. Searching by class name and text/content description...")
                sendButton = findNodesByClassName(currentRootNodeForSend, "android.widget.Button").firstOrNull { node: AccessibilityNodeInfo ->
                    node.text?.toString()?.contains("Send", ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains("Send", ignoreCase = true) == true
                }
                if (sendButton != null) {
                    LogUtils.d(TAG, "Found send button by class name and text/content description search.")
                }
            }

            if (sendButton == null) {
                LogUtils.e(TAG, "WhatsApp send button not found after trying multiple methods and $maxRetries retries. Dumping tree for analysis.")
                dumpNodeTree(currentRootNodeForSend) // Dump tree if send button not found
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_send_button_not_found") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
                return
            }

            LogUtils.d(TAG, "Found send button. Enabled: ${sendButton.isEnabled}, Clickable: ${sendButton.isClickable}, Actions: ${sendButton.actionList}")
            Thread.sleep(100) // FIX: Using Thread.sleep() // Small delay before clicking

            val clickSuccess = retryOperationBlocking(maxRetries, retryDelayMs) { // FIX: Call retryOperationBlocking
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (clickSuccess) {
                LogUtils.d(TAG, "Send button clicked successfully with retry.")
                methodChannel?.invokeMethod("whatsappReplyStatus", "success") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
            } else {
                LogUtils.e(TAG, "Failed to click send button after retries.")
                methodChannel?.invokeMethod("whatsappReplyStatus", "failed_click_send") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
            }

        } catch (e: Exception) {
            LogUtils.e(TAG, "Error during WhatsApp reply process: ${e.message}", e)
            methodChannel?.invokeMethod("whatsappReplyStatus", "failed_exception: ${e.message}") ?: LogUtils.w(TAG, "MethodChannel is null. Could not send status to Flutter.")
        } finally {
            // Only recycle the rootNode obtained at the start of performReplyAction
            rootNode.recycle()
            LogUtils.d(TAG, "Root node recycled.")
        }
    }

    private fun findNodesByContentDescription(node: AccessibilityNodeInfo?, description: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node == null) return result

        if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                result.addAll(findNodesByContentDescription(child, description))
                child.recycle() // Recycle child nodes after use in recursive search
            }
        }
        return result
    }

    private fun findNodesByClassName(node: AccessibilityNodeInfo?, className: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node == null) return result

        if (node.className?.toString()?.equals(className, ignoreCase = true) == true) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                result.addAll(findNodesByClassName(child, className))
                child.recycle() // Recycle child nodes after use in recursive search
            }
        }
        return result
    }

    private fun findNodesByText(node: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node == null) return result

        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                result.addAll(findNodesByText(child, text))
                child.recycle() // Recycle child nodes after use in recursive search
            }
        }
        return result
    }

    // Helper function to find the chat title node
    private fun findChatTitleNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Common IDs for chat title (might vary)
        val chatTitleIds = listOf(
            "com.whatsapp:id/conversation_title", // Common ID for conversation title in chat screen
            "com.whatsapp:id/contact_name", // For direct chats
            "com.whatsapp:id/group_name", // For group chats
            "com.whatsapp:id/toolbar_title" // Another common ID for title in toolbar
        )

        // Try by ID first
        for (id in chatTitleIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                LogUtils.d(TAG, "Found chat title node by ID: $id")
                nodes.forEachIndexed { index, n -> if (index != 0) n.recycle() }
                return nodes[0]
            }
        }

        // Fallback: Search for TextViews that are likely titles (less reliable, based on heuristics)
        val toolbarNodes = findNodesByClassName(rootNode, "android.widget.Toolbar")
        if (toolbarNodes.isNotEmpty()) {
            val toolbar = toolbarNodes[0]
            val textViewNodes = findNodesByClassName(toolbar, "android.widget.TextView")
            if (textViewNodes.isNotEmpty()) {
                for (node: AccessibilityNodeInfo in textViewNodes) {
                    if (!node.text.isNullOrEmpty()) {
                        LogUtils.d(TAG, "Found chat title node within Toolbar (TextView with text: '${node.text}').")
                        textViewNodes.forEachIndexed { index, n -> if (n != node) n.recycle() }
                        toolbar.recycle()
                        return node
                    }
                }
            }
            toolbar.recycle()
        }

        LogUtils.w(TAG, "Chat title node not found by common IDs or Toolbar heuristics.")
        return null
    }

    // Helper method to dump the accessibility tree for debugging
    private fun dumpNodeTree(node: AccessibilityNodeInfo?, indent: String = "") {
        if (node == null) return
        LogUtils.v(TAG, "$indent- Class: ${node.className}, Text: '${node.text}', ContentDesc: '${node.contentDescription}', ID: ${node.viewIdResourceName}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNodeTree(child, indent + "  ")
                child.recycle() // Recycle child nodes after use in recursive dump
            }
        }
    }
}
