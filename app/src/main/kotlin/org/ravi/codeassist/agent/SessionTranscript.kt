package org.ravi.codeassist.agent

/**
 * Structured session memory for the chat-based agent. Owns the PLAN checklist,
 * the rolling observation log, and recent-action bookkeeping, and renders the
 * <ACTIVE_PLAN> / <RECENT_OBSERVATION_LOG> blocks that are re-injected into
 * every feedback round. Compaction is built in so long chat sessions (the
 * whole prompt is typed as the first user message) stay inside the model's
 * context window instead of drifting.
 */
class SessionTranscript {

    data class PlanTask(val text: String, val done: Boolean = false)

    private val plan = mutableListOf<PlanTask>()
    private var planNote: String? = null

    /** Rolling history of recent transaction digests, oldest first. */
    private val observations = java.util.ArrayDeque<String>()

    /** Recent high-level actions for the live status line, newest first. */
    private val recentActions = java.util.ArrayDeque<String>()

    val planTasks: List<PlanTask> get() = plan.toList()
    val pendingPlanCount: Int get() = plan.count { !it.done }
    val observationLog: List<String> get() = observations.toList()

    /** Most recently recorded (distinct) digest, or null before any round. */
    val lastObservation: String? get() = observations.peekLast()

    val latestAction: String get() = recentActions.firstOrNull() ?: "Idle"

    fun reset() {
        plan.clear()
        planNote = null
        observations.clear()
        recentActions.clear()
    }

    fun recordAction(action: String) {
        recentActions.addFirst(action)
        while (recentActions.size > MAX_RECENT_ACTIONS) recentActions.removeLast()
    }

    /**
     * Applies a [org.ravi.codeassist.CodeCommand.Plan]: a non-empty tasks list
     * replaces the stored checklist (all items reset to pending); doneNumbers
     * mark 1-based items complete; note records progress.
     */
    fun applyPlan(command: org.ravi.codeassist.CodeCommand.Plan) {
        if (command.tasks.isNotEmpty()) {
            plan.clear()
            command.tasks.filter { it.isNotBlank() }.take(MAX_PLAN_ITEMS)
                .forEach { plan.add(PlanTask(it.trim(), false)) }
        }
        if (command.doneNumbers.isNotEmpty()) {
            command.doneNumbers.filter { it in 1..plan.size }.distinct().sorted().forEach { n ->
                plan[n - 1] = plan[n - 1].copy(done = true)
            }
        }
        if (command.note.isNotBlank()) planNote = command.note.take(MAX_PLAN_NOTE_CHARS)
    }

    /**
     * Compacts a transaction result into a short digest for the rolling log:
     * the decision-relevant FILE STATUS lines (failures, policy blocks, and
     * workspace mutations only — bare READ/OUTLINE successes are dropped as
     * noise) plus the BATCH SUMMARY line. Falls back to a truncated first-lines
     * summary when the markers are absent (e.g. a plan-only or parse-error round).
     */
    fun buildObservationDigest(logs: String): String {
        val statusBlock = Regex("(?s)--- FILE STATUS ---\n(.*?)(?=--- BATCH SUMMARY ---)").find(logs)
        val summaryLine = logs.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("BATCH SUMMARY") || it.startsWith("Commands executed") }
        val sb = StringBuilder()
        if (statusBlock != null) {
            statusBlock.groupValues[1].lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && digestLineKept(it) }
                .take(12)
                .forEach { sb.append(it).append('\n') }
        }
        summaryLine?.let { sb.append(it).append('\n') }
        val compact = sb.toString().trimEnd().take(MAX_OBSERVATION_CHARS)
        return compact.ifBlank { logs.lineSequence().filter { it.isNotBlank() }.take(2).joinToString(" | ").take(160) }
    }

    /**
     * Digest noise filter: keep failures and policy blocks, plus successes that
     * actually mutated the workspace (Patch/Create/Delete/Move). A bare
     * `[SUCCESS] Read ...` line carries no decision-relevant info and only pads
     * the rolling log.
     */
    private fun digestLineKept(line: String): Boolean {
        if (line.contains("FAILED") || line.contains("BLOCKED")) return true
        if (!line.startsWith("[SUCCESS] ")) return false
        val commandName = line.removePrefix("[SUCCESS] ").substringBefore(' ').substringBefore('|').trim()
        return commandName in DIGEST_KEEP_SUCCESS_COMMANDS
    }

    /** Records a transaction digest (compacted by [buildObservationDigest]);
     *  consecutive duplicates are collapsed so repeated identical rounds don't
     *  stack noise in the log. */
    fun addObservation(digest: String) {
        if (digest.isBlank()) return
        if (digest == observations.peekLast()) return
        observations.addLast(digest)
        while (observations.size > MAX_OBSERVATION_HISTORY) observations.removeFirst()
    }

    /** Rendered rolling history re-injected with each feedback round. Pass
     *  excludeLatest = true when the newest digest summarizes the very message
     *  it would be attached to, so the BATCH SUMMARY isn't repeated verbatim. */
    fun observationLogSection(excludeLatest: Boolean = false): String {
        val items = observations.toList()
        if (excludeLatest && items.isNotEmpty()) return renderObservationLog(items.dropLast(1))
        return renderObservationLog(items)
    }

    private fun renderObservationLog(items: List<String>): String {
        if (items.isEmpty()) return ""
        return buildString {
            appendLine("<RECENT_OBSERVATION_LOG — rolling history of recent decisions/results, newest last. Anchor your next action on the latest entry; earlier entries are context, not instructions.>")
            items.forEach { digest ->
                appendLine("  " + digest.replace("\n", "\n  "))
            }
        }.trimEnd()
    }

    /**
     * Rendered plan checklist re-injected on every feedback round so the model
     * never loses track of a multi-step goal inside a long ReAct loop.
     */
    fun planSection(): String {
        if (plan.isEmpty()) {
            return "<NO_ACTIVE_PLAN — if this is a multi-step goal, declare one with [COMMAND: PLAN] + [CONTENT] checklist so progress is tracked across rounds.>"
        }
        return buildString {
            appendLine("<ACTIVE_PLAN — authoritative checklist. Keep it current.>")
            plan.forEachIndexed { index, task ->
                val mark = if (task.done) "[x]" else "[ ]"
                appendLine("$mark ${index + 1}. ${task.text}")
            }
            planNote?.let { appendLine("Latest progress note: $it") }
            appendLine("Update: [COMMAND: PLAN] with revised [CONTENT]; progress via [PLAN_DONE: n] / [PLAN_NOTE: ...].")
        }
    }

    companion object {
        private const val MAX_OBSERVATION_HISTORY = 5
        private const val MAX_OBSERVATION_CHARS = 400
        private const val MAX_RECENT_ACTIONS = 8
        private const val MAX_PLAN_ITEMS = 20
        private const val MAX_PLAN_NOTE_CHARS = 400
        private val DIGEST_KEEP_SUCCESS_COMMANDS = setOf("Patch", "Create", "Delete", "Move")
    }
}
