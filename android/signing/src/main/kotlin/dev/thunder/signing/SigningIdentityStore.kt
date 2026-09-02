package dev.thunder.signing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SigningIdentityStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "signing-identities")
    private val mutex = Mutex()

    suspend fun getExisting(targetPackageName: String): SigningIdentity? = withContext(Dispatchers.IO) {
        validateTargetPackageName(targetPackageName)

        mutex.withLock {
            val file = File(directory, IdentityNames.storageName(targetPackageName))
            if (file.isFile) load(file, targetPackageName) else null
        }
    }

    suspend fun getOrCreate(targetPackageName: String): SigningIdentity = withContext(Dispatchers.IO) {
        validateTargetPackageName(targetPackageName)

        mutex.withLock {
            val file = File(directory, IdentityNames.storageName(targetPackageName))
            if (file.isFile) load(file, targetPackageName) else create(file, targetPackageName)
        }
    }

    suspend fun exportPortable(targetPackageName: String, passphrase: CharArray): ByteArray =
        withContext(Dispatchers.IO) {
            validateTargetPackageName(targetPackageName)
            mutex.withLock {
                val file = File(directory, IdentityNames.storageName(targetPackageName))
                if (!file.isFile) throw SigningException(SigningFailureCode.IDENTITY_CORRUPT)
                PortableSigningIdentityBackup.create(load(file, targetPackageName), passphrase)
            }
        }

    fun inspectPortable(backup: ByteArray): SigningIdentityBackupMetadata =
        PortableSigningIdentityBackup.metadata(backup)

    suspend fun restorePortable(
        targetPackageName: String,
        backup: ByteArray,
        passphrase: CharArray,
        replacementConfirmed: Boolean,
    ): SigningIdentity = withContext(Dispatchers.IO) {
        validateTargetPackageName(targetPackageName)
        val restored = PortableSigningIdentityBackup.restore(backup, passphrase, targetPackageName)
        mutex.withLock {
            val file = File(directory, IdentityNames.storageName(targetPackageName))
            PortableSigningIdentityBackup.requireReplacementAllowed(
                activeCertificateSha256 = if (file.isFile) {
                    runCatching { load(file, targetPackageName).certificateSha256 }.getOrNull()
                        ?: UNKNOWN_ACTIVE_CERTIFICATE
                } else {
                    null
                },
                restoredCertificateSha256 = restored.certificateSha256,
                replacementConfirmed = replacementConfirmed,
            )
            persist(file, restored, replace = file.isFile)
            restored
        }
    }

    private fun validateTargetPackageName(targetPackageName: String) {
        try {
            IdentityNames.validatePackageName(targetPackageName)
        } catch (error: IllegalArgumentException) {
            throw SigningException(SigningFailureCode.INVALID_TARGET, error)
        }
    }

    private fun create(file: File, targetPackageName: String): SigningIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val keyId = UUID.randomUUID().toString()
        val certificate = selfSignedCertificate(keyPair.public, keyPair.private, keyId)
        return SigningIdentity(
            keyId = keyId,
            targetPackageName = targetPackageName,
            certificateSha256 = IdentityNames.sha256(certificate.encoded),
            certificate = certificate,
            privateKey = keyPair.private,
        ).also { persist(file, it, replace = false) }
    }

    private fun persist(file: File, identity: SigningIdentity, replace: Boolean) {
        val privateBytes = requireNotNull(identity.privateKey.encoded) { "Signing key is not exportable" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(createIfMissing = true))
            cipher.updateAAD(IdentityNames.additionalAuthenticatedData(identity.targetPackageName))
            val ciphertext = cipher.doFinal(privateBytes)
            val payload = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("keyId", identity.keyId)
                put("targetPackageName", identity.targetPackageName)
                put("algorithm", "RSA-3072")
                put("certificate", identity.certificate.encoded.toBase64())
                put("iv", cipher.iv.toBase64())
                put("encryptedPrivateKey", ciphertext.toBase64())
            }.toString()
            atomicWrite(file, payload, replace)
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.IDENTITY_STORAGE_FAILED, error)
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun load(file: File, expectedPackageName: String): SigningIdentity {
        val privateBytes: ByteArray
        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("schemaVersion") != SCHEMA_VERSION) {
                throw SigningException(SigningFailureCode.IDENTITY_CORRUPT)
            }
            if (json.getString("targetPackageName") != expectedPackageName) {
                throw SigningException(SigningFailureCode.IDENTITY_CORRUPT)
            }
            if (json.getString("algorithm") != "RSA-3072") {
                throw SigningException(SigningFailureCode.IDENTITY_CORRUPT)
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(createIfMissing = false),
                GCMParameterSpec(128, json.getString("iv").fromBase64()),
            )
            cipher.updateAAD(IdentityNames.additionalAuthenticatedData(expectedPackageName))
            privateBytes = cipher.doFinal(json.getString("encryptedPrivateKey").fromBase64())

            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            } finally {
                privateBytes.fill(0)
            }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(json.getString("certificate").fromBase64().inputStream()) as X509Certificate
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)

            return SigningIdentity(
                keyId = json.getString("keyId"),
                targetPackageName = expectedPackageName,
                certificateSha256 = IdentityNames.sha256(certificate.encoded),
                certificate = certificate,
                privateKey = privateKey,
            )
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.IDENTITY_CORRUPT, error)
        }
    }

    private fun wrappingKey(createIfMissing: Boolean): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }
            if (!createIfMissing) {
                throw SigningException(SigningFailureCode.KEYSTORE_UNAVAILABLE)
            }

            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        WRAPPING_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        } catch (error: SigningException) {
            throw error
        } catch (error: Exception) {
            throw SigningException(SigningFailureCode.KEYSTORE_UNAVAILABLE, error)
        }
    }

    private fun selfSignedCertificate(
        publicKey: java.security.PublicKey,
        privateKey: java.security.PrivateKey,
        keyId: String,
    ): X509Certificate {
        val random = SecureRandom()
        val subject = X500Name("CN=Thunder $keyId")
        val notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS))
        val notAfter = Date.from(Instant.now().plus(20 * 365L, ChronoUnit.DAYS))
        val serial = BigInteger(160, random).abs().max(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            publicKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer)).also {
            it.checkValidity()
            it.verify(publicKey)
        }
    }

    private fun atomicWrite(file: File, content: String, replace: Boolean) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw SigningException(SigningFailureCode.IDENTITY_STORAGE_FAILED)
        }
        if (file.exists() && !replace) throw SigningException(SigningFailureCode.IDENTITY_STORAGE_FAILED)
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_KEY_ALIAS = "thunder.signing.wrap.v1"
        val UNKNOWN_ACTIVE_CERTIFICATE = "0".repeat(64)
    }
}
