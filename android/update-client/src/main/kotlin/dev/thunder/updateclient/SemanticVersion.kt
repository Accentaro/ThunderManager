package dev.thunder.updateclient

import java.math.BigInteger

class SemanticVersion private constructor(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>,
    val buildMetadata: List<String>,
) : Comparable<SemanticVersion> {
    val isStable: Boolean
        get() = prerelease.isEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }

        val count = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until count) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val result = when {
                leftNumeric && rightNumeric -> BigInteger(left).compareTo(BigInteger(right))
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion &&
        major == other.major && minor == other.minor && patch == other.patch &&
        prerelease == other.prerelease && buildMetadata == other.buildMetadata

    override fun hashCode(): Int {
        var result = major.hashCode()
        result = 31 * result + minor.hashCode()
        result = 31 * result + patch.hashCode()
        result = 31 * result + prerelease.hashCode()
        return 31 * result + buildMetadata.hashCode()
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) append('-').append(prerelease.joinToString("."))
        if (buildMetadata.isNotEmpty()) append('+').append(buildMetadata.joinToString("."))
    }

    companion object {
        fun parse(value: String): SemanticVersion {
            require(value.length in 5..MAX_LENGTH) { "Semantic version length is invalid" }
            val match = PATTERN.matchEntire(value)
                ?: throw IllegalArgumentException("Semantic version is invalid")
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
            if (prerelease.any { identifier ->
                    identifier.all(Char::isDigit) && identifier.length > 1 && identifier.startsWith('0')
                }
            ) {
                throw IllegalArgumentException("Numeric prerelease identifiers cannot contain leading zeroes")
            }
            val build = match.groupValues[5]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
            return SemanticVersion(
                major = BigInteger(match.groupValues[1]),
                minor = BigInteger(match.groupValues[2]),
                patch = BigInteger(match.groupValues[3]),
                prerelease = prerelease,
                buildMetadata = build,
            )
        }

        fun parseStableRelease(value: String): SemanticVersion = parse(value).also {
            require(it.isStable && it.buildMetadata.isEmpty()) { "Release version must be stable x.y.z SemVer" }
        }

        private const val MAX_LENGTH = 128
        private val PATTERN = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )
    }
}
