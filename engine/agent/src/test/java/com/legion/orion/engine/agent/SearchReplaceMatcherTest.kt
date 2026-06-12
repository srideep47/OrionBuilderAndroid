package com.legion.orion.engine.agent

import com.legion.orion.engine.agent.tools.PlaceholderGuard
import com.legion.orion.engine.agent.tools.SearchReplaceMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchReplaceMatcherTest {

    @Test fun exactMatch() {
        val text = "val a = 1\nval b = 2\n"
        val out = SearchReplaceMatcher.replaceFirst(text, "val b = 2", "val b = 3")
        assertEquals("val a = 1\nval b = 3\n", out)
    }

    @Test fun indentationTolerantMatch() {
        val text = "fun f() {\n        return 1\n}\n"        // 8-space indent
        val search = "    return 1"                            // 4-space indent in the search
        val out = SearchReplaceMatcher.replaceFirst(text, search, "    return 2")
        assertNotNull(out)
        assertEquals("fun f() {\n    return 2\n}\n", out)
    }

    @Test fun multiLineBlockMatch() {
        val text = "a\nb\nc\nd\n"
        val out = SearchReplaceMatcher.replaceFirst(text, "b\nc", "B\nC")
        assertEquals("a\nB\nC\nd\n", out)
    }

    @Test fun noMatchReturnsNull() {
        assertNull(SearchReplaceMatcher.replaceFirst("a\nb\n", "zzz", "x"))
    }

    @Test fun firstOccurrenceOnly() {
        val text = "x\nx\n"
        val out = SearchReplaceMatcher.replaceFirst(text, "x", "y")
        assertEquals("y\nx\n", out)
    }

    @Test fun placeholderGuardCatchesElisions() {
        assertNotNull(PlaceholderGuard.findViolation("fun f() {\n  // ... rest of code\n}"))
        assertNotNull(PlaceholderGuard.findViolation("# ... existing code ..."))
        assertNotNull(PlaceholderGuard.findViolation("// TODO: implement"))
    }

    @Test fun placeholderGuardAllowsRealCode() {
        assertNull(PlaceholderGuard.findViolation("fun add(a: Int, b: Int) = a + b // adds two ints"))
        assertNull(PlaceholderGuard.findViolation("const x = [1, 2, 3] // a list"))
    }
}
