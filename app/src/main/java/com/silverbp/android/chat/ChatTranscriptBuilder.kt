package com.silverbp.android.chat

import kotlin.math.ceil

/**
 * Trims a chat transcript to fit a token budget while preserving conversational
 * coherence and the latest image attachment.
 *
 * Invariants (covered by [com.silverbp.android.chat.ChatTranscriptBuilderTest]):
 *  1. The system block is always retained.
 *  2. The latest user turn is always retained, regardless of size — the latest
 *     image lives on this turn (see GemmaChatService / AICoreChatService).
 *  3. Older turns are dropped in pairs (user+assistant) from the oldest end so
 *     a half-pair never gets fed back to the model.
 *  4. When ≥2 turns are dropped a synthetic Assistant-role marker
 *     `[earlier turns omitted: N]` is inserted right after the system block so
 *     the model sees the gap rather than a silent jump.
 *  5. If the system block + latest user turn alone already exceed the budget,
 *     the result is `[system, lastUser]` and the backend will truncate
 *     naturally — better than refusing to answer.
 *
 * Token estimate is a CJK-friendly heuristic (`ceil(len / 2.0) + 4`) — exact
 * tokenization differs by backend but this is close enough for budgeting and
 * cheap to compute.
 */
object ChatTranscriptBuilder {

    private const val MARKER_TEMPLATE = "[earlier turns omitted: %d]"

    fun build(
        systemBlock: String,
        history: List<ChatMessage>,
        budget: Int,
    ): List<ChatMessage> {
        val systemMsg = ChatMessage(role = ChatMessage.Role.System, text = systemBlock)
        if (history.isEmpty()) return listOf(systemMsg)

        // Find the latest user turn — invariant #2 says we must keep it whatever happens.
        val lastUserIdx = history.indexOfLast { it.role == ChatMessage.Role.User }
        if (lastUserIdx < 0) {
            // No user turns at all (degenerate) — just return system + history verbatim.
            return listOf(systemMsg) + history
        }
        val lastUser = history[lastUserIdx]

        // Earlier-than-last-user history, paired up oldest→newest. We'll add pairs
        // from the newest end while they fit; the rest gets dropped + marker.
        val earlier = history.subList(0, lastUserIdx)
        val pairs = pairUp(earlier)

        val systemTokens = estimateTokens(systemMsg.text)
        val lastUserTokens = estimateTokens(lastUser.text)
        var used = systemTokens + lastUserTokens

        if (used > budget) {
            // Invariant #5: even a minimal transcript blows the budget — let the backend truncate.
            return listOf(systemMsg, lastUser)
        }

        val keptPairs = ArrayDeque<List<ChatMessage>>()
        // Walk newest pair → oldest pair, accept while we stay under budget.
        for (i in pairs.indices.reversed()) {
            val pair = pairs[i]
            val cost = pair.sumOf { estimateTokens(it.text) }
            if (used + cost > budget) break
            keptPairs.addFirst(pair)
            used += cost
        }

        val droppedPairs = pairs.size - keptPairs.size
        val droppedTurns = droppedPairs * 2

        val out = ArrayList<ChatMessage>(2 + droppedTurns + keptPairs.size * 2 + 1)
        out.add(systemMsg)
        if (droppedTurns >= 2) {
            out.add(
                ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = MARKER_TEMPLATE.format(droppedTurns),
                ),
            )
        }
        for (pair in keptPairs) out.addAll(pair)
        out.add(lastUser)
        return out
    }

    /**
     * Group a flat history (excluding the latest user turn) into user→assistant
     * pairs. A trailing or leading orphan (user without assistant or vice
     * versa) is wrapped in a singleton list and treated atomically.
     */
    private fun pairUp(history: List<ChatMessage>): List<List<ChatMessage>> {
        if (history.isEmpty()) return emptyList()
        val pairs = mutableListOf<List<ChatMessage>>()
        var i = 0
        while (i < history.size) {
            val cur = history[i]
            val next = history.getOrNull(i + 1)
            if (cur.role == ChatMessage.Role.User &&
                next != null && next.role == ChatMessage.Role.Assistant
            ) {
                pairs.add(listOf(cur, next))
                i += 2
            } else {
                pairs.add(listOf(cur))
                i += 1
            }
        }
        return pairs
    }

    /**
     * Cheap CJK-friendly token estimate. Real tokenizers differ per backend; we
     * just need a budget number that is in the right order of magnitude. The
     * `+4` covers role markers / newlines added by flatten functions.
     */
    fun estimateTokens(text: String): Int =
        ceil(text.length / 2.0).toInt() + 4
}
