package com.legion.orion.marta

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** First-party navigation requests from AppControl. OrionApp collects [routes]. */
@Singleton
class MartaNavigator @Inject constructor() {
    private val _routes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val routes = _routes.asSharedFlow()

    fun open(route: String) {
        _routes.tryEmit(route)
    }
}
