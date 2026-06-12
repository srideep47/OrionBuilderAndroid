package com.legion.orion.engine.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wraps a provider so a retryable mid-stream failure is resumed instead of lost: the partial
 * assistant text accumulated so far is re-sent as [ChatRequest.assistantPrefix] and generation
 * continues. Already-emitted deltas are not repeated downstream.
 *
 * This is one of the desktop Orion Builder's known weaknesses (no stream recovery), fixed here
 * by design and applied to every provider via the resolver.
 */
fun ChatProvider.withRecovery(maxRetries: Int = 2): ChatProvider {
    val delegate = this
    return object : ChatProvider {
        override val toolCallingMode = delegate.toolCallingMode

        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            val accumulated = StringBuilder(request.assistantPrefix.orEmpty())
            var attempt = 0
            var currentRequest = request

            while (true) {
                var sawRetryableError = false
                var errored: StreamEvent.Error? = null

                delegate.stream(currentRequest).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            accumulated.append(event.text)
                            emit(event)
                        }
                        is StreamEvent.Error -> {
                            if (event.retryable && attempt < maxRetries) {
                                sawRetryableError = true
                            } else {
                                errored = event
                            }
                        }
                        else -> emit(event)
                    }
                }

                when {
                    sawRetryableError -> {
                        attempt++
                        currentRequest = request.copy(assistantPrefix = accumulated.toString())
                        // loop: resume from the accumulated prefix
                    }
                    errored != null -> {
                        emit(errored!!)
                        return@flow
                    }
                    else -> return@flow // clean completion
                }
            }
        }
    }
}
