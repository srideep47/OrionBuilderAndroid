package com.legion.orion.engine.agent.tools

/**
 * Rejects file writes that contain lazy placeholders instead of real code — a recurring failure
 * mode of the desktop agent. Patterns are matched case-insensitively against comment-style elisions.
 */
object PlaceholderGuard {

    private val patterns = listOf(
        Regex("""//\s*\.\.\.\s*(rest|existing|unchanged|previous)""", RegexOption.IGNORE_CASE),
        Regex("""#\s*\.\.\.\s*(rest|existing|unchanged|previous)""", RegexOption.IGNORE_CASE),
        Regex("""/\*\s*\.\.\.\s*(rest|existing|unchanged)""", RegexOption.IGNORE_CASE),
        Regex("""\.\.\.\s*existing code\s*\.\.\.""", RegexOption.IGNORE_CASE),
        Regex("""(//|#)\s*rest of (the )?(code|file|implementation)""", RegexOption.IGNORE_CASE),
        Regex("""(//|#)\s*(keep|leave) (the )?(existing|rest)""", RegexOption.IGNORE_CASE),
        Regex("""(//|#)\s*TODO:?\s*implement""", RegexOption.IGNORE_CASE),
        Regex("""(//|#)\s*\.\.\.\s*$""", RegexOption.IGNORE_CASE),
    )

    /** Returns the offending snippet if [content] looks elided, or null if it's complete. */
    fun findViolation(content: String): String? {
        for (p in patterns) {
            p.find(content)?.let { return it.value.trim() }
        }
        return null
    }
}
