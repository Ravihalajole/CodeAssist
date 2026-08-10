package org.ravi.codeassist.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

object PackageManagerUtils {
    fun getInstalledApplications(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        // No flags: GET_META_DATA would force the system to load every app's
        // merged manifest metadata into memory; we only need the label & flags,
        // so the flag was wasted work and excessive allocation on low-end devices.
        @Suppress("DEPRECATION")
        val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(0)
        }
        return packages.map { appInfo ->
            InstalledApp(
                packageName = appInfo.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedBy { it.appName }
    }
}