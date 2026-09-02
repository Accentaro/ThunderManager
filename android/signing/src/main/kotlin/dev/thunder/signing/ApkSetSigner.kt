package dev.thunder.signing

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ApkSetSigner {
    suspend fun sign(
        inputs: List<File>,
        outputDirectory: File,
        identity: SigningIdentity,
    ): SignedApkSet = withContext(Dispatchers.IO) {
        if (inputs.isEmpty() || inputs.any { !it.isFile || !it.canRead() || it.length() <= 0L }) {
            throw SigningException(SigningFailureCode.INPUT_INVALID)
        }
        if (outputDirectory.exists() || !outputDirectory.mkdirs()) {
            throw SigningException(SigningFailureCode.OUTPUT_UNAVAILABLE)
        }

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "Thunder ${identity.keyId}",
            identity.privateKey,
            listOf(identity.certificate),
            false,
        ).build()
        val outputs = mutableListOf<SignedApkArtifact>()

        try {
            inputs.forEachIndexed { index, input ->
                val output = File(outputDirectory, artifactName(index))
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(input)
                    .setOutputApk(output)
                    .setMinSdkVersion(28)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setV4SigningEnabled(false)
                    .build()
                    .sign()

                val verification = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(28).build().verify()
                if (!verification.isVerified) {
                    throw SigningException(SigningFailureCode.VERIFICATION_FAILED)
                }
                val signerDigests = verification.signerCertificates
                    .map { IdentityNames.sha256(it.encoded) }
                    .distinct()
                if (signerDigests != listOf(identity.certificateSha256)) {
                    throw SigningException(SigningFailureCode.VERIFICATION_FAILED)
                }

                outputs += SignedApkArtifact(
                    inputFile = input,
                    outputFile = output,
                    size = output.length(),
                    sha256 = IdentityNames.sha256(output),
                )
            }

            SignedApkSet(identity.keyId, identity.certificateSha256, outputs)
        } catch (error: SigningException) {
            outputDirectory.deleteRecursively()
            throw error
        } catch (error: Exception) {
            outputDirectory.deleteRecursively()
            throw SigningException(SigningFailureCode.SIGNING_FAILED, error)
        }
    }

    private fun artifactName(index: Int): String =
        if (index == 0) "base.apk" else "split-${index.toString().padStart(3, '0')}.apk"
}
