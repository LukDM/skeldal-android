package cz.gates.skeldal.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {
    @Test
    fun normalizesSeparators() {
        assertEquals("maps/LESPRED.MAP", PathRules.normalize("maps\\LESPRED.MAP"))
        assertEquals("a/b", PathRules.normalize("./a//b/"))
        assertEquals("Brány Skeldalu/SKELDAL.DDL", PathRules.normalize("Brány Skeldalu/SKELDAL.DDL"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertNull(PathRules.normalize("../evil.so"))
        assertNull(PathRules.normalize("maps/../../evil"))
        assertNull(PathRules.normalize("/etc/passwd"))
        assertNull(PathRules.normalize("C:\\Windows\\x.dll"))
        assertNull(PathRules.normalize("a\u0000b"))
        assertNull(PathRules.normalize(""))
    }

    @Test
    fun safeNames() {
        assertTrue(PathRules.isSafeName("TRACK01.MUS"))
        assertTrue(PathRules.isSafeName("Brány Skeldalu"))
        assertFalse(PathRules.isSafeName(".."))
        assertFalse(PathRules.isSafeName("a/b"))
    }
}
