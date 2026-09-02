package dev.thunder.injection.custom

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ReactNativeDexWeaverTest {
    @Test
    fun rewriteParsesOnceAndLeavesExactVerificationToTheBackend() {
        var parses = 0
        val weaver = DexlibReactNativeDexWeaver { parses++ }
        val original = fixtureDex(bridgeCalls = false, untouchedLiteral = 1)

        val rewritten = weaver.rewrite(original)
        assertEquals("Candidate serialization must not repeat the two-DEX structural verification", 1, parses)

        weaver.verify(original, rewritten)
        assertEquals("The authoritative exact verification parses only its input and output", 3, parses)
        assertEquals(ReactNativeCallCounts(original = 0, patched = 2), weaver.callCounts(rewritten))
        assertEquals(4, parses)
    }

    @Test
    fun exactVerificationStillRejectsAnUnallowlistedInstructionChange() {
        val weaver = DexlibReactNativeDexWeaver()
        val original = fixtureDex(bridgeCalls = false, untouchedLiteral = 1)
        val tampered = fixtureDex(bridgeCalls = true, untouchedLiteral = 2)

        assertThrows(IOException::class.java) {
            weaver.verify(original, tampered)
        }
    }

    private fun fixtureDex(bridgeCalls: Boolean, untouchedLiteral: Int): ByteArray {
        val callTarget = if (bridgeCalls) {
            ImmutableMethodReference(
                "Ldev/thunder/bootstrap/ThunderReactNativeBridge;",
                "loadJSBundle",
                listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                "V",
            )
        } else {
            ImmutableMethodReference(
                "Lcom/facebook/react/runtime/ReactInstance;",
                "loadJSBundle",
                listOf("Lcom/facebook/react/bridge/JSBundleLoader;"),
                "V",
            )
        }
        val callOpcode = if (bridgeCalls) Opcode.INVOKE_STATIC else Opcode.INVOKE_VIRTUAL
        val seamMethods = listOf("first", "second").map { name ->
            ImmutableMethod(
                HOST_CLASS,
                name,
                emptyList(),
                "V",
                PUBLIC_STATIC,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        ImmutableInstruction35c(callOpcode, 2, 0, 1, 0, 0, 0, callTarget),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    emptyList(),
                    emptyList(),
                ),
            )
        }
        val seamClass = ImmutableClassDef(
            HOST_CLASS,
            PUBLIC,
            "Ljava/lang/Object;",
            emptyList(),
            "Host.kt",
            emptySet(),
            emptyList(),
            seamMethods,
        )
        val untouchedClass = ImmutableClassDef(
            "Lfixture/Untouched;",
            PUBLIC,
            "Ljava/lang/Object;",
            emptyList(),
            "Untouched.kt",
            emptySet(),
            emptyList(),
            listOf(
                ImmutableMethod(
                    "Lfixture/Untouched;",
                    "value",
                    emptyList(),
                    "V",
                    PUBLIC_STATIC,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(
                        1,
                        listOf(
                            ImmutableInstruction11n(Opcode.CONST_4, 0, untouchedLiteral),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        emptyList(),
                        emptyList(),
                    ),
                ),
            ),
        )
        val output = MemoryDataStore()
        return try {
            DexPool.writeTo(output, ImmutableDexFile(Opcodes.getDefault(), listOf(seamClass, untouchedClass)))
            output.data
        } finally {
            output.close()
        }
    }

    private companion object {
        const val HOST_CLASS = "Lfixture/Host;"
        const val PUBLIC = 0x1
        const val PUBLIC_STATIC = 0x9
    }
}
