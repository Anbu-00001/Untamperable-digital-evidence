package com.realitylock.app

import com.realitylock.app.core.config.ProofPackageConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit test (no Android framework dependency) verifying the
 * proof-package identity constants stay internally consistent. Serves as the
 * seed of the Phase-6 test suite and proves the test toolchain is wired.
 */
class ProofPackageConstantsTest {

    @Test
    fun schemaUrnEndsWithSchemaVersion() {
        assertTrue(
            "SCHEMA_URN must embed SCHEMA_VERSION so the two never drift apart",
            ProofPackageConstants.SCHEMA_URN.endsWith(ProofPackageConstants.SCHEMA_VERSION),
        )
    }

    @Test
    fun canonicalizationSchemeIsJcs() {
        assertEquals("RFC8785", ProofPackageConstants.JSON_CANONICALIZATION_SCHEME)
    }
}
