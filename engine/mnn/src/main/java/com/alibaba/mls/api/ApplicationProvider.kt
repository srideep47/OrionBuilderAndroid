// Minimal replacement for GPTee's ApplicationProvider (com.alibaba.mls.api).
// Kept at the original package so the ported engine sources compile unchanged.
package com.alibaba.mls.api

import android.app.Application

object ApplicationProvider {
    private lateinit var application: Application

    @JvmStatic
    fun set(app: Application) {
        application = app
    }

    @JvmStatic
    fun get(): Application = application
}
