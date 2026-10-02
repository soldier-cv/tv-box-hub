package com.boxhub

import android.content.Context

/** Holds the application context so the device layer can be context-free. */
object Ctx {
    @Volatile
    var app: Context? = null

    fun require(): Context = app ?: error("BoxHub context not initialised")
}