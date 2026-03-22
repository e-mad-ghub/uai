package com.mad.screenagent

/**
 * Compile-time feature flags. Set to `true` to enable, `false` to disable.
 * Disabled features are fully removed from the UI with no regressions on other features.
 */
object FeatureFlags {
    /** Agora multi-agent chat rooms. */
    const val AGORA_ENABLED = false
}
