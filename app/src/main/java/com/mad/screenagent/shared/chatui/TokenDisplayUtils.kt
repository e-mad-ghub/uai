package com.mad.screenagent.shared.chatui

fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}
