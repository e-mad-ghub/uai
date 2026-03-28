package com.mad.screenagent

import android.content.pm.ServiceInfo
import android.os.Build
import com.mad.screenagent.feature.bubble.foregroundServiceTypeMaskForOverlayService
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypeTest {

    @Test
    fun overlayService_usesSpecialUseOnlyOnAndroid14WhenIdle() {
        val typeMask = foregroundServiceTypeMaskForOverlayService(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            includeMediaProjection = false
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, typeMask)
    }

    @Test
    fun overlayService_addsMediaProjectionOnlyDuringCaptureOnAndroid14() {
        val typeMask = foregroundServiceTypeMaskForOverlayService(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            includeMediaProjection = true
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            typeMask
        )
    }

    @Test
    fun overlayService_keepsLegacyMediaProjectionTypeOnAndroid10WhenIdle() {
        val typeMask = foregroundServiceTypeMaskForOverlayService(
            sdkInt = Build.VERSION_CODES.Q,
            includeMediaProjection = false
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, typeMask)
    }

    @Test
    fun overlayService_usesMediaProjectionTypeDuringLegacyCaptureOnAndroid10() {
        val typeMask = foregroundServiceTypeMaskForOverlayService(
            sdkInt = Build.VERSION_CODES.Q,
            includeMediaProjection = true
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, typeMask)
    }
}
