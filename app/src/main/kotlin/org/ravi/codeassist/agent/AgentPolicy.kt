package org.ravi.codeassist.agent

import org.ravi.codeassist.CodeCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Policy rules for the chat-based agent, persisted in CodeAssistPrefs as a
 * JSON array and enforced as hard execution blocks.
 *
 * Rule format (one per line in the settings editor):
 *   deny PATCH:**/secrets/**     (command uppercase, optional; path glob optional)
 * A missing command matches every command type; a missing glob matches every
 * path. Only DENY is enforced at execution time — ask/allow stay on the
 * existing AutoAllowMode + destructive-confirmation gates (mobile-friendly:
 * DELETE/MOVE always require human confirmation, no rule can weaken that).
 */
object AgentPolicy {

    enum class Effect { ALLOW, ASK, DENY }

    data class Rule(val effect: Effect, val command: String?, val pathGlob: String?) {
        fun render(): String {
            val parts = listOf(effect.name, command ?: "*", pathGlob ?: "*")
            return parts.joinToString(" ")
        }
    }

    private const val PREFS_KEY = "POLICY_RULES"
    const val MAX_RULES = 20

    fun rulesFor(prefs: android.content.SharedPreferences?): List<Rule> {
        val raw = prefs?.getString(PREFS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val effect = runCatching { Effect.valueOf(obj.optString("effect")) }.getOrNull() ?: continue
                    add(
                        Rule(
                            effect = effect,
                            command = obj.optString("command").ifBlank { null },
                            pathGlob = obj.optString("pathGlob").ifBlank { null }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRules(prefs: android.content.SharedPreferences, rules: List<Rule>) {
        val arr = JSONArray()
        rules.take(MAX_RULES).forEach { rule ->
            arr.put(
                JSONObject()
                    .put("effect", rule.effect.name)
                    .put("command", rule.command ?: "")
                    .put("pathGlob", rule.pathGlob ?: "")
            )
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    /** Parses editor lines; malformed lines are dropped and returned as errors. */
    fun parseLines(lines: List<String>): Pair<List<Rule>, List<String>> {
        val rules = mutableListOf<Rule>()
        val errors = mutableListOf<String>()
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val tokens = line.split(Regex("\\s+"))
            if (tokens.isEmpty() || tokens[0].uppercase() !in setOf("ALLOW", "ASK", "DENY")) {
                errors.add("Malformed line: \"$line\"")
                return@forEach
            }
            val effect = Effect.valueOf(tokens[0].uppercase())
            val command = tokens.getOrNull(1)?.takeIf { it != "*" }?.uppercase()
            val pathGlob = tokens.getOrNull(2)?.takeIf { it != "*" }
            rules.add(Rule(effect, command, pathGlob))
        }
        return Pair(rules.take(MAX_RULES), errors)
    }

    /** Deny effect for a command under the given rules. */
    fun effectFor(rules: List<Rule>, command: CodeCommand): Effect {
        val target = targetPath(command)
        for (rule in rules) {
            if (rule.effect != Effect.DENY) continue
            if (rule.command != null && rule.command != command.javaClass.simpleName.uppercase()) continue
            if (rule.pathGlob != null && target != null && !matchesGlob(rule.pathGlob, target)) continue
            if (rule.pathGlob != null && target == null) continue
            return Effect.DENY
        }
        return Effect.ALLOW
    }

    /** Primary path a command operates on, or null when it has no single path. */
    fun targetPath(command: CodeCommand): String? = when (command) {
        is CodeCommand.Patch -> command.path
        is CodeCommand.Create -> command.path
        is CodeCommand.Delete -> command.path
        is CodeCommand.Move -> command.oldPath
        is CodeCommand.Read -> command.path
        is CodeCommand.Outline -> command.path
        is CodeCommand.Grep -> command.path
        else -> null
    }

    /** <POLICY> block injected into the system prompt so the model plans around rules. */
    fun policySection(rules: List<Rule>): String {
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("<POLICY — workspace rules enforced by the app. A DENY blocks execution outright; plan around it, do not attempt to bypass.>")
            rules.forEach { rule ->
                appendLine("- DENY ${rule.command ?: "*"}${if (rule.pathGlob != null) " on $rule.pathGlob" else ""}")
            }
        }
    }

    private fun matchesGlob(glob: String, path: String): Boolean {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i++
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '.', '\\', '|' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return try {
            java.util.regex.Pattern.compile(sb.toString(), java.util.regex.Pattern.CASE_INSENSITIVE).matcher(path).matches()
        } catch (_: Exception) {
            false
        }
    }
}
