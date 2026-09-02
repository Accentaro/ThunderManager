package dev.thunder.signing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityNamesTest {
    @Test
    fun storageNameIsStableAndPathSafe() {
        val name = IdentityNames.storageName("com.discord")
        assertEquals(69, name.length)
        assertEquals(true, name.endsWith(".json"))
        assertEquals(false, name.contains('/'))
    }

    @Test
    fun identityBindingChangesWithPackage() {
        assertNotEquals(
            IdentityNames.additionalAuthenticatedData("com.discord").toList(),
            IdentityNames.additionalAuthenticatedData("com.discord.beta").toList(),
        )
    }

    @Test
    fun rejectsPathLikeAndSingleSegmentTargets() {
        assertThrows(IllegalArgumentException::class.java) { IdentityNames.storageName("../discord") }
        assertThrows(IllegalArgumentException::class.java) { IdentityNames.storageName("discord") }
    }
}
