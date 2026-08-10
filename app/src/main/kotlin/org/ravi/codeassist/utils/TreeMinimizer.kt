package org.ravi.codeassist.utils

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object TreeMinimizer {

    /**
     * Traverses the active window hierarchy and compiles a highly compressed JSON 
     * representation containing strictly actionable or semantic leaf nodes.
     */
    fun flatten(root: AccessibilityNodeInfo?): String {
        if (root == null) return "[]"
        val nodes = mutableListOf<SemanticNode>()
        traverse(root, nodes)
        return serializeToJson(nodes)
    }

    private fun traverse(
        node: AccessibilityNodeInfo?, 
        result: MutableList<SemanticNode>, 
        screenWidth: Int = android.content.res.Resources.getSystem().displayMetrics.widthPixels,
        screenHeight: Int = android.content.res.Resources.getSystem().displayMetrics.heightPixels
    ) {
        if (node == null) return

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else null

        // Pruning Logic: Keep the node only if it provides interactive utility or semantic context
        val hasMeaningfulText = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()
        
        if (isClickable || isEditable || hasMeaningfulText) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Exclude nodes that are off-screen, invisible, or too deep
            if (bounds.width() > 0 && bounds.height() > 0 && 
                bounds.right > 0 && bounds.bottom > 0 && 
                bounds.left < screenWidth && bounds.top < screenHeight) {
                val id = node.viewIdResourceName ?: ""
                val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val displayText = text ?: contentDesc
                
                result.add(
                    SemanticNode(
                        nodeId = id,
                        type = className,
                        text = displayText,
                        hint = hint,
                        isClickable = isClickable,
                        isEditable = isEditable
                    )
                )
            }
        }

        // Recursively evaluate children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverse(child, result, screenWidth, screenHeight)
            child?.recycle()
        }
    }

    private fun serializeToJson(nodes: List<SemanticNode>): String {
        val array = JSONArray()
        for (node in nodes) {
            val obj = JSONObject()
            if (node.nodeId.isNotEmpty()) obj.put("node_id", node.nodeId)
            obj.put("type", node.type)
            if (!node.text.isNullOrBlank()) obj.put("text", node.text)
            if (!node.hint.isNullOrBlank()) obj.put("hint", node.hint)
            
            if (node.isClickable) obj.put("clickable", true)
            if (node.isEditable) obj.put("editable", true)
            
            array.put(obj)
        }
        return array.toString()
    }
}