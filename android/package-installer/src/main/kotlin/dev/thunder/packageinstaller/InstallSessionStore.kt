package dev.thunder.packageinstaller

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException

class InstallSessionStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "install-sessions")

    fun create(stagedInstall: StagedInstall, state: InstallSessionState): InstallSessionRecord =
        synchronized(PROCESS_LOCK) {
            val record = InstallSessionRecord(
                stagedInstall = stagedInstall,
                state = state,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            write(record, createOnly = true)
            record
        }

    fun transition(
        sessionId: Int,
        expectedStates: Set<InstallSessionState>,
        state: InstallSessionState,
        statusCode: Int? = null,
        statusMessage: String? = null,
    ): InstallSessionRecord? = synchronized(PROCESS_LOCK) {
        val previous = readLocked(sessionId)
            ?: throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        if (previous.state !in expectedStates) return@synchronized null
        val record = previous.copy(
            state = state,
            statusCode = statusCode,
            statusMessage = statusMessage?.take(MAX_STATUS_MESSAGE_LENGTH),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        write(record, createOnly = false)
        record
    }

    fun read(sessionId: Int): InstallSessionRecord? = synchronized(PROCESS_LOCK) {
        readLocked(sessionId)
    }

    private fun readLocked(sessionId: Int): InstallSessionRecord? {
        if (sessionId < 0) return null
        val file = recordFile(sessionId)
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_RECORD_BYTES) {
            throw InstallException(InstallFailureCode.JOURNAL_FAILED)
        }

        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("schema") != SCHEMA || json.getInt("sessionId") != sessionId) {
                throw InstallException(InstallFailureCode.JOURNAL_FAILED)
            }
            val staged = StagedInstall(
                sessionId = sessionId,
                packageName = json.getString("packageName"),
                artifactCount = json.getInt("artifactCount"),
                totalBytes = json.getLong("totalBytes"),
                createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
            )
            InstallInputRules.requirePackageName(staged.packageName)
            InstallSessionRecord(
                stagedInstall = staged,
                state = InstallSessionState.valueOf(json.getString("state")),
                statusCode = if (json.isNull("statusCode")) null else json.getInt("statusCode"),
                statusMessage = if (json.isNull("statusMessage")) null else json.getString("statusMessage"),
                updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
            )
        } catch (error: InstallException) {
            throw error
        } catch (error: Exception) {
            throw InstallException(InstallFailureCode.JOURNAL_FAILED, error)
        }
    }

    fun records(): List<InstallSessionRecord> = synchronized(PROCESS_LOCK) {
        val files = root.listFiles()?.filter { it.isFile && it.name.matches(RECORD_NAME_PATTERN) }.orEmpty()
        files.map { readLocked(it.name.removeSuffix(".json").toInt())!! }
            .sortedByDescending { it.updatedAtEpochMillis }
    }

    private fun write(record: InstallSessionRecord, createOnly: Boolean) {
        try {
            if (!root.exists() && !root.mkdirs()) throw IOException("Could not create install journal")
            val file = recordFile(record.stagedInstall.sessionId)
            if (createOnly && file.exists()) throw IOException("Install journal already exists")
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
                output.write(serialize(record).toString().toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: Exception) {
                atomicFile.failWrite(output)
                throw error
            }
        } catch (error: InstallException) {
            throw error
        } catch (error: Exception) {
            throw InstallException(InstallFailureCode.JOURNAL_FAILED, error)
        }
    }

    private fun serialize(record: InstallSessionRecord) = JSONObject().apply {
        put("schema", SCHEMA)
        put("sessionId", record.stagedInstall.sessionId)
        put("packageName", record.stagedInstall.packageName)
        put("artifactCount", record.stagedInstall.artifactCount)
        put("totalBytes", record.stagedInstall.totalBytes)
        put("createdAtEpochMillis", record.stagedInstall.createdAtEpochMillis)
        put("state", record.state.name)
        put("statusCode", record.statusCode ?: JSONObject.NULL)
        put("statusMessage", record.statusMessage ?: JSONObject.NULL)
        put("updatedAtEpochMillis", record.updatedAtEpochMillis)
    }

    private fun recordFile(sessionId: Int) = File(root, "$sessionId.json")

    private companion object {
        const val SCHEMA = 1
        const val MAX_RECORD_BYTES = 16L * 1024L
        const val MAX_STATUS_MESSAGE_LENGTH = 512
        val RECORD_NAME_PATTERN = Regex("^[0-9]+\\.json$")
        val PROCESS_LOCK = Any()
    }
}
