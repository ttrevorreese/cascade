package com.cascade.alarm.engine

import android.util.Log

/** Logcat tag for the alarm engine. scripts/e2e-alarm.sh greps for these lines. */
object EngineLog {
    const val TAG = "CascadeEngine"

    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, error: Throwable? = null) = Log.w(TAG, message, error)
    fun e(message: String, error: Throwable? = null) = Log.e(TAG, message, error)
}
