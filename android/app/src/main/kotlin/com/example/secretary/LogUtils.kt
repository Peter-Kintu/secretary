package com.example.secretary

import android.util.Log

// Utility object for controlled logging
object LogUtils {
    private const val TAG_PREFIX = "AI-Assistant/" // Optional prefix for easier logcat filtering
    var logLevel = Log.DEBUG // Can be updated at runtime for dynamic log level control

    // Removed the explicit setLogLevel function to resolve platform declaration clash.
    // You can now set the log level directly: LogUtils.logLevel = Log.VERBOSE

    fun v(tag: String, message: String) {
        if (logLevel <= Log.VERBOSE) Log.v(TAG_PREFIX + tag, message)
    }

    fun d(tag: String, message: String) {
        if (logLevel <= Log.DEBUG) Log.d(TAG_PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        if (logLevel <= Log.INFO) Log.i(TAG_PREFIX + tag, message)
    }

    fun w(tag: String, message: String) {
        if (logLevel <= Log.WARN) Log.w(TAG_PREFIX + tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (logLevel <= Log.ERROR) {
            if (throwable != null) {
                Log.e(TAG_PREFIX + tag, message, throwable)
            } else {
                Log.e(TAG_PREFIX + tag, message)
            }
        }
    }

    /**
     * Logs a message at the ASSERT (What a Terrible Failure) level.
     * This is for conditions that should never happen.
     * @param tag Used to identify the source of a log message.
     * @param message The message to be logged.
     */
    fun wtf(tag: String, message: String) {
        if (logLevel <= Log.ASSERT) Log.wtf(TAG_PREFIX + tag, message)
    }

    /**
     * Logs a message with a specified priority.
     * @param priority The log priority (e.g., Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT).
     * @param tag Used to identify the source of a log message.
     * @param message The message to be logged.
     */
    fun log(priority: Int, tag: String, message: String) {
        if (logLevel <= priority) Log.println(priority, TAG_PREFIX + tag, message)
    }
}
