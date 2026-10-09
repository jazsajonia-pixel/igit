package app.igit

import app.igit.data.Validation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test fun acceptsValidRepositoryParts() {
        assertTrue(Validation.isValidRepo("owner-name", "project_1"))
        assertFalse(Validation.isValidRepo("../owner", "repo"))
    }

    @Test fun requiresHttpsAiEndpoints() {
        assertTrue(Validation.isValidAiBaseUrl("https://api.example.com/v1"))
        assertFalse(Validation.isValidAiBaseUrl("http://api.example.com/v1"))
        assertFalse(Validation.isValidAiBaseUrl("https://user:pass@example.com/v1"))
    }

    @Test fun rejectsUnsafeRelativePaths() {
        assertTrue(Validation.isSafeRelativePath("src/main.kt"))
        assertFalse(Validation.isSafeRelativePath("../../secrets"))
        assertFalse(Validation.isSafeRelativePath("/absolute/path"))
    }
}
