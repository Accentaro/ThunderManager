package dev.thunder.signing

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object PortableSigningIdentityBackup {
    fun create(
        identity: SigningIdentity,
        passphrase: CharArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        validatePassphrase(passphrase)
        validateIdentity(identity)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val plaintext = encodeIdentity(identity)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(authenticatedHeader(identity.metadata(), salt, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            JSONObject().apply {
                put("schema", SCHEMA)
                put("cipher", CIPHER)
                put("keyId", identity.keyId)
                put("targetPackageName", identity.targetPackageName)
                put("certificateSha256", identity.certificateSha256)
                put("kdf", JSONObject().apply {
                    put("name", KDF)
                    put("version", ARGON_VERSION)
                    put("memoryKiB", ARGON_MEMORY_KIB)
                    put("iterations", ARGON_ITERATIONS)
                    put("parallelism", ARGON_PARALLELISM)
                    put("salt", salt.toBase64())
                })
                put("nonce", nonce.toBase64())
                put("ciphertext", ciphertext.toBase64())
            }.toString().toByteArray(Charsets.UTF_8).also {
                if (it.size > MAX_BACKUP_BYTES) throw SigningException(SigningFailureCode.BACKUP_FAILED)
            }
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.BACKUP_FAILED, error)
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
    }

    fun metadata(backup: ByteArray): SigningIdentityBackupMetadata = parseEnvelope(backup).metadata

    fun restore(
        backup: ByteArray,
        passphrase: CharArray,
        expectedTargetPackageName: String,
    ): SigningIdentity {
        validatePassphrase(passphrase)
        try {
            IdentityNames.validatePackageName(expectedTargetPackageName)
        } catch (error: IllegalArgumentException) {
            throw SigningException(SigningFailureCode.INVALID_TARGET, error)
        }
        val envelope = parseEnvelope(backup)
        if (envelope.metadata.targetPackageName != expectedTargetPackageName) {
            throw SigningException(SigningFailureCode.BACKUP_TARGET_MISMATCH)
        }
        val key = deriveKey(passphrase, envelope.salt)
        val plaintext = try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, envelope.nonce),
            )
            cipher.updateAAD(authenticatedHeader(envelope.metadata, envelope.salt, envelope.nonce))
            cipher.doFinal(envelope.ciphertext)
        } catch (error: AEADBadTagException) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_PASSWORD, error)
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT, error)
        } finally {
            key.fill(0)
        }
        return try {
            decodeIdentity(plaintext, envelope.metadata)
        } finally {
            plaintext.fill(0)
        }
    }

    fun requireReplacementAllowed(
        activeCertificateSha256: String?,
        restoredCertificateSha256: String,
        replacementConfirmed: Boolean,
    ) {
        if (activeCertificateSha256 == null) return
        if (!SHA_256.matches(activeCertificateSha256) || !SHA_256.matches(restoredCertificateSha256)) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        }
        if (!replacementConfirmed) throw SigningException(SigningFailureCode.BACKUP_ACTIVE_IDENTITY)
    }

    private fun parseEnvelope(backup: ByteArray): Envelope {
        if (backup.isEmpty() || backup.size > MAX_BACKUP_BYTES) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        }
        try {
            val root = JSONObject(String(backup, Charsets.UTF_8))
            root.requireKeys(ROOT_KEYS)
            if (root.getInt("schema") != SCHEMA || root.getString("cipher") != CIPHER) {
                throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            }
            val keyId = root.getString("keyId").also { UUID.fromString(it) }
            val targetPackageName = root.getString("targetPackageName").also(IdentityNames::validatePackageName)
            val certificateSha256 = root.getString("certificateSha256")
            if (!SHA_256.matches(certificateSha256)) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            val kdf = root.getJSONObject("kdf").also { it.requireKeys(KDF_KEYS) }
            if (
                kdf.getString("name") != KDF ||
                kdf.getInt("version") != ARGON_VERSION ||
                kdf.getInt("memoryKiB") != ARGON_MEMORY_KIB ||
                kdf.getInt("iterations") != ARGON_ITERATIONS ||
                kdf.getInt("parallelism") != ARGON_PARALLELISM
            ) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            return Envelope(
                metadata = SigningIdentityBackupMetadata(keyId, targetPackageName, certificateSha256),
                salt = kdf.getString("salt").fromBase64(SALT_BYTES),
                nonce = root.getString("nonce").fromBase64(NONCE_BYTES),
                ciphertext = root.getString("ciphertext")
                    .fromBase64(minimum = TAG_BITS / 8, maximum = MAX_CIPHERTEXT_BYTES),
            )
        } catch (error: SigningException) {
            throw error
        } catch (error: JSONException) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT, error)
        } catch (error: IllegalArgumentException) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT, error)
        }
    }

    private fun encodeIdentity(identity: SigningIdentity): ByteArray {
        val certificate = identity.certificate.encoded
        val privateKey = identity.privateKey.encoded
            ?: throw SigningException(SigningFailureCode.BACKUP_FAILED)
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(PAYLOAD_SCHEMA)
                    output.writeBounded(identity.keyId.toByteArray(Charsets.UTF_8), MAX_KEY_ID_BYTES)
                    output.writeBounded(identity.targetPackageName.toByteArray(Charsets.UTF_8), MAX_PACKAGE_BYTES)
                    output.writeBounded(certificate, MAX_CERTIFICATE_BYTES)
                    output.writeBounded(privateKey, MAX_PRIVATE_KEY_BYTES)
                }
                bytes.toByteArray()
            }
        } finally {
            privateKey.fill(0)
        }
    }

    private fun decodeIdentity(
        plaintext: ByteArray,
        metadata: SigningIdentityBackupMetadata,
    ): SigningIdentity {
        try {
            val input = DataInputStream(ByteArrayInputStream(plaintext))
            if (input.readInt() != PAYLOAD_SCHEMA) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            val keyId = String(input.readBounded(MAX_KEY_ID_BYTES), Charsets.UTF_8)
            val targetPackageName = String(input.readBounded(MAX_PACKAGE_BYTES), Charsets.UTF_8)
            val certificateBytes = input.readBounded(MAX_CERTIFICATE_BYTES)
            val privateBytes = input.readBounded(MAX_PRIVATE_KEY_BYTES)
            if (input.read() != -1 || keyId != metadata.keyId || targetPackageName != metadata.targetPackageName) {
                throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            }
            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            } finally {
                privateBytes.fill(0)
            }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateBytes.inputStream()) as X509Certificate
            val identity = SigningIdentity(
                keyId = keyId,
                targetPackageName = targetPackageName,
                certificateSha256 = IdentityNames.sha256(certificate.encoded),
                certificate = certificate,
                privateKey = privateKey,
            )
            if (!MessageDigest.isEqual(
                    identity.certificateSha256.toByteArray(Charsets.US_ASCII),
                    metadata.certificateSha256.toByteArray(Charsets.US_ASCII),
                )
            ) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            validateIdentity(identity)
            return identity
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT, error)
        }
    }

    private fun validateIdentity(identity: SigningIdentity) {
        try {
            IdentityNames.validatePackageName(identity.targetPackageName)
            UUID.fromString(identity.keyId)
            if (identity.privateKey.algorithm != "RSA" || identity.certificate.publicKey.algorithm != "RSA") {
                throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            }
            val publicKey = identity.certificate.publicKey as? RSAKey
                ?: throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            val privateKey = identity.privateKey as? RSAKey
                ?: throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            if (publicKey.modulus.bitLength() != RSA_BITS || privateKey.modulus != publicKey.modulus) {
                throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            }
            identity.certificate.checkValidity()
            identity.certificate.verify(identity.certificate.publicKey)
            if (!IdentityNames.sha256(identity.certificate.encoded).equals(identity.certificateSha256, true)) {
                throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
            }
            val proof = "Thunder signing identity continuity".toByteArray(Charsets.UTF_8)
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(identity.privateKey)
                update(proof)
                sign()
            }
            val verified = Signature.getInstance("SHA256withRSA").run {
                initVerify(identity.certificate.publicKey)
                update(proof)
                verify(signature)
            }
            if (!verified) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT, error)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val password = passphrase.copyOf()
        return try {
            ByteArray(KEY_BYTES).also { output ->
                Argon2BytesGenerator().apply {
                    init(
                        Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                            .withVersion(ARGON_VERSION)
                            .withMemoryAsKB(ARGON_MEMORY_KIB)
                            .withIterations(ARGON_ITERATIONS)
                            .withParallelism(ARGON_PARALLELISM)
                            .withSalt(salt)
                            .build(),
                    )
                }.generateBytes(password, output)
            }
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.BACKUP_FAILED, error)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun authenticatedHeader(
        metadata: SigningIdentityBackupMetadata,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray = listOf(
        "thunder-signing-backup-v1",
        metadata.keyId,
        metadata.targetPackageName,
        metadata.certificateSha256,
        KDF,
        ARGON_VERSION.toString(),
        ARGON_MEMORY_KIB.toString(),
        ARGON_ITERATIONS.toString(),
        ARGON_PARALLELISM.toString(),
        salt.toBase64(),
        nonce.toBase64(),
    ).joinToString("\u0000").toByteArray(Charsets.UTF_8)

    private fun SigningIdentity.metadata() = SigningIdentityBackupMetadata(
        keyId = keyId,
        targetPackageName = targetPackageName,
        certificateSha256 = certificateSha256,
    )

    private fun validatePassphrase(passphrase: CharArray) {
        if (passphrase.size !in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_PASSWORD)
        }
    }

    private fun JSONObject.requireKeys(expected: Set<String>) {
        if (keys().asSequence().toSet() != expected) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        }
    }

    private fun DataOutputStream.writeBounded(value: ByteArray, maximum: Int) {
        if (value.isEmpty() || value.size > maximum) throw SigningException(SigningFailureCode.BACKUP_FAILED)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBounded(maximum: Int): ByteArray {
        val length = readInt()
        if (length !in 1..maximum || length > available()) {
            throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        }
        return ByteArray(length).also(::readFully)
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().withoutPadding().encodeToString(this)

    private fun String.fromBase64(exact: Int): ByteArray = fromBase64(exact, exact)

    private fun String.fromBase64(minimum: Int, maximum: Int): ByteArray {
        val value = Base64.getDecoder().decode(this)
        if (value.size !in minimum..maximum) throw SigningException(SigningFailureCode.BACKUP_INVALID_FORMAT)
        return value
    }

    private data class Envelope(
        val metadata: SigningIdentityBackupMetadata,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private const val SCHEMA = 1
    private const val PAYLOAD_SCHEMA = 1
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KDF = "Argon2id"
    private const val ARGON_VERSION = Argon2Parameters.ARGON2_VERSION_13
    private const val ARGON_MEMORY_KIB = 64 * 1024
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 1
    private const val RSA_BITS = 3072
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val MIN_PASSPHRASE_CHARS = 8
    private const val MAX_PASSPHRASE_CHARS = 256
    private const val MAX_BACKUP_BYTES = 128 * 1024
    private const val MAX_CIPHERTEXT_BYTES = 96 * 1024
    private const val MAX_KEY_ID_BYTES = 64
    private const val MAX_PACKAGE_BYTES = 255
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private const val MAX_PRIVATE_KEY_BYTES = 32 * 1024
    private val SHA_256 = Regex("^[0-9a-f]{64}$")
    private val ROOT_KEYS = setOf(
        "schema", "cipher", "keyId", "targetPackageName", "certificateSha256", "kdf", "nonce", "ciphertext",
    )
    private val KDF_KEYS = setOf("name", "version", "memoryKiB", "iterations", "parallelism", "salt")
}
