package com.mad.screenagent

import com.mad.screenagent.shared.streaming.ProviderFlowControlException
import com.mad.screenagent.shared.streaming.rethrowIfProviderFlowControl
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderFlowControlTest {

    @Test
    fun rethrowIfProviderFlowControl_rethrowsInternalControlExceptions() {
        val failure = object : ProviderFlowControlException() {}

        assertThrows(ProviderFlowControlException::class.java) {
            rethrowIfProviderFlowControl(failure)
        }
    }

    @Test
    fun rethrowIfProviderFlowControl_ignoresNormalExceptions() {
        rethrowIfProviderFlowControl(IllegalStateException("normal provider failure"))
    }
}
