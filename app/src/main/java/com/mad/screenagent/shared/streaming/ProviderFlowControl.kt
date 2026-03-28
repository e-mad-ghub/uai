package com.mad.screenagent.shared.streaming

/**
 * Internal control-flow exception used to stop or reroute an upstream provider stream.
 *
 * This must never be converted into a user-facing StreamChunk.Error inside a `flow {}` builder,
 * otherwise Kotlin Flow will report exception-transparency violations.
 */
internal open class ProviderFlowControlException(
    cause: Throwable? = null
) : RuntimeException(null, cause, false, false)

internal fun rethrowIfProviderFlowControl(exception: Throwable) {
    if (exception is ProviderFlowControlException) {
        throw exception
    }
}
