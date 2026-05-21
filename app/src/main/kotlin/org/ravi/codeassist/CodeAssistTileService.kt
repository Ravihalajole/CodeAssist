package org.ravi.codeassist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class CodeAssistTileService : TileService() {

  override fun onClick() {
    super.onClick()

    val intent =
            Intent(this, ClipboardActivity::class.java).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

    // Android 14 (API 34) requires PendingIntent to start activities from a Tile
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pendingIntent =
              PendingIntent.getActivity(
                      this,
                      0,
                      intent,
                      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
              )
      startActivityAndCollapse(pendingIntent)
    } else {
      // For older Android versions
      @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
  }
}
