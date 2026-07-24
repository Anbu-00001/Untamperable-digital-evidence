package com.realitylock.app.capture

import android.os.Build
import com.realitylock.app.capture.model.DeviceData
import com.realitylock.app.core.config.AppConfig
import com.realitylock.app.core.device.InstallIdProvider

/**
 * Assembles the device block of a proof package.
 *
 * Everything here is either an install-scoped identifier or non-sensitive build
 * metadata — no hardware serial, IMEI, or `ANDROID_ID` (research/03 §5).
 */
class DeviceInfoProvider(private val installIdProvider: InstallIdProvider) {

    fun current(): DeviceData = DeviceData(
        installId = installIdProvider.getOrCreate(),
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        sdkInt = Build.VERSION.SDK_INT,
        appVersionName = AppConfig.versionName,
        appVersionCode = AppConfig.versionCode,
    )
}
