package org.ravi.codeassist.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.ravi.codeassist.database.ElementRole
import org.ravi.codeassist.database.ElementSignature

object SignatureExtractor {

    fun extract(
        node: AccessibilityNodeInfo,
        profileId: Long,
        role: ElementRole
    ): ElementSignature {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else null

        return ElementSignature(
            profileId = profileId,
            role = role,
            resourceId = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
            hintText = hint,
            boundsX = bounds.centerX(),
            boundsY = bounds.centerY(),
            hierarchyPath = generateXPath(node),
            className = node.className?.toString()
        )
    }

    fun generateXPath(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        var current = node
        val path = StringBuilder()

        try {
            while (current != null) {
                val parent = current.parent ?: break
                var index = 0
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i)
                    if (child == current) {
                        index = i
                        child?.recycle()
                        break
                    }
                    child?.recycle()
                }
                val className = current.className?.toString()?.substringAfterLast('.') ?: "Node"
                path.insert(0, "/$className[$index]")
                
                val oldCurrent = current
                current = parent
                if (oldCurrent != node) {
                    oldCurrent.recycle()
                }
            }
        } catch (e: Exception) {
            // Failsafe for broken node trees
        }
        
        return path.toString()
    }
}