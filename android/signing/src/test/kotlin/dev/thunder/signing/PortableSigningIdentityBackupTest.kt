package dev.thunder.signing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

class PortableSigningIdentityBackupTest {
    @Test
    fun restoredIdentityKeepsTheExactApkSigner() {
        val original = identity()
        val before = sign(original, PAYLOAD)
        val backup = PortableSigningIdentityBackup.create(original, PASSPHRASE.copyOf())

        val metadata = PortableSigningIdentityBackup.metadata(backup)
        val restored = PortableSigningIdentityBackup.restore(
            backup,
            PASSPHRASE.copyOf(),
            TARGET_PACKAGE,
        )

        assertEquals(original.keyId, metadata.keyId)
        assertEquals(original.certificateSha256, metadata.certificateSha256)
        assertEquals(original.certificateSha256, restored.certificateSha256)
        assertArrayEquals(before, sign(restored, PAYLOAD))
        assertEquals(true, verify(original, sign(restored, PAYLOAD), PAYLOAD))
        assertEquals(true, verify(restored, before, PAYLOAD))
    }

    @Test
    fun wrongPasswordNeverProducesAnIdentity() {
        val backup = PortableSigningIdentityBackup.create(identity(), PASSPHRASE.copyOf())
        val error = assertThrows(SigningException::class.java) {
            PortableSigningIdentityBackup.restore(backup, "different password".toCharArray(), TARGET_PACKAGE)
        }
        assertEquals(SigningFailureCode.BACKUP_INVALID_PASSWORD, error.code)
    }

    @Test
    fun corruptedAndTruncatedBackupsFailClosed() {
        val backup = PortableSigningIdentityBackup.create(identity(), PASSPHRASE.copyOf())
        val corrupted = backup.copyOf().also { bytes ->
            val index = bytes.indexOfLast { it == 'A'.code.toByte() || it == 'B'.code.toByte() }
            bytes[index] = if (bytes[index] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        }
        assertThrows(SigningException::class.java) {
            PortableSigningIdentityBackup.restore(corrupted, PASSPHRASE.copyOf(), TARGET_PACKAGE)
        }
        assertEquals(
            SigningFailureCode.BACKUP_INVALID_FORMAT,
            assertThrows(SigningException::class.java) {
                PortableSigningIdentityBackup.restore(
                    backup.copyOf(backup.size / 2),
                    PASSPHRASE.copyOf(),
                    TARGET_PACKAGE,
                )
            }.code,
        )
    }

    @Test
    fun invalidFormatAndWrongTargetAreRejected() {
        assertEquals(
            SigningFailureCode.BACKUP_INVALID_FORMAT,
            assertThrows(SigningException::class.java) {
                PortableSigningIdentityBackup.metadata("not a backup".toByteArray())
            }.code,
        )
        val backup = PortableSigningIdentityBackup.create(identity(), PASSPHRASE.copyOf())
        assertEquals(
            SigningFailureCode.BACKUP_TARGET_MISMATCH,
            assertThrows(SigningException::class.java) {
                PortableSigningIdentityBackup.restore(backup, PASSPHRASE.copyOf(), "dev.thunder.other")
            }.code,
        )
    }

    @Test
    fun activeIdentityCannotBeReplacedWithoutExplicitConfirmation() {
        val digest = identity().certificateSha256
        val replacement = "f".repeat(64)
        assertEquals(
            SigningFailureCode.BACKUP_ACTIVE_IDENTITY,
            assertThrows(SigningException::class.java) {
                PortableSigningIdentityBackup.requireReplacementAllowed(digest, replacement, false)
            }.code,
        )
        PortableSigningIdentityBackup.requireReplacementAllowed(digest, replacement, true)
        PortableSigningIdentityBackup.requireReplacementAllowed(null, replacement, false)
    }

    @Test
    fun weakPassphrasesAreRejectedBeforeEncryption() {
        assertEquals(
            SigningFailureCode.BACKUP_INVALID_PASSWORD,
            assertThrows(SigningException::class.java) {
                PortableSigningIdentityBackup.create(identity(), "short".toCharArray())
            }.code,
        )
    }

    private fun identity(): SigningIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val keyId = UUID.randomUUID().toString()
        val subject = X500Name("CN=Thunder $keyId")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(42),
                Date.from(Instant.now().minus(1, ChronoUnit.DAYS)),
                Date.from(Instant.now().plus(3650, ChronoUnit.DAYS)),
                subject,
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
        return SigningIdentity(
            keyId = keyId,
            targetPackageName = TARGET_PACKAGE,
            certificateSha256 = IdentityNames.sha256(certificate.encoded),
            certificate = certificate,
            privateKey = keyPair.private,
        )
    }

    private fun sign(identity: SigningIdentity, bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(identity.privateKey)
            update(bytes)
            sign()
        }

    private fun verify(identity: SigningIdentity, signature: ByteArray, bytes: ByteArray): Boolean =
        Signature.getInstance("SHA256withRSA").run {
            initVerify(identity.certificate.publicKey)
            update(bytes)
            verify(signature)
        }

    private companion object {
        const val TARGET_PACKAGE = "dev.thunder.app"
        val PASSPHRASE = "correct horse battery staple".toCharArray()
        val PAYLOAD = "same APK signer proof".toByteArray()
    }
}
