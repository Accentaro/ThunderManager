package dev.thunder.injection.custom

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class ChatBubblesDexWeaverTest {
    @Test
    fun inspectRewriteAndVerifyProduceOnlySameWidthCallsAndExactHelpers() {
        var parses = 0
        val weaver = DexlibChatBubblesDexWeaver { parses++ }
        val original = fixtureDex()

        assertEquals(ChatBubblesDexStatus.UNPATCHED, weaver.inspect(original))
        assertEquals(1, parses)

        val rewritten = weaver.rewrite(original)
        assertEquals(2, parses)
        assertEquals(ChatBubblesDexStatus.PATCHED, weaver.inspect(rewritten))
        assertEquals(3, parses)

        weaver.verify(original, rewritten)
        assertEquals(5, parses)

        val beforeCalls = setMessageCalls(parse(original))
        val afterCalls = setMessageCalls(parse(rewritten))
        assertEquals(2, beforeCalls.size)
        assertEquals(2, afterCalls.size)
        beforeCalls.zip(afterCalls).forEach { (before, after) ->
            assertEquals(Opcode.INVOKE_DIRECT, before.opcode)
            assertEquals(Opcode.INVOKE_STATIC, after.opcode)
            assertEquals(before.codeUnits, after.codeUnits)
            assertEquals(registerShape(before), registerShape(after))
        }

        val messageView = parse(rewritten).classes.single { it.type == MESSAGE_VIEW }
        assertExactHelper(
            messageView,
            "thunder\$configureAccessoriesMargin",
            "configureAccessoriesMargin",
            "thunderBeforeConfigureAccessoriesMargin",
            "thunderAfterConfigureAccessoriesMargin",
        )
        assertExactHelper(
            messageView,
            "thunder\$configureAuthor",
            "configureAuthor",
            "thunderBeforeConfigureAuthor",
            "thunderAfterConfigureAuthor",
        )
    }

    @Test
    fun rangeInvokesAreRewrittenWithoutChangingWidthOrRegisters() {
        val weaver = DexlibChatBubblesDexWeaver()
        val original = fixtureDex(rangeCalls = true)

        val rewritten = weaver.rewrite(original)
        weaver.verify(original, rewritten)

        val beforeCalls = setMessageCalls(parse(original))
        val afterCalls = setMessageCalls(parse(rewritten))
        beforeCalls.zip(afterCalls).forEach { (before, after) ->
            assertEquals(Opcode.INVOKE_DIRECT_RANGE, before.opcode)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after.opcode)
            assertEquals(before.codeUnits, after.codeUnits)
            assertEquals(registerShape(before), registerShape(after))
        }
    }

    @Test
    fun unsupportedHostScanningIsBoundedAndDoesNotThrow() {
        var parses = 0
        val weaver = DexlibChatBubblesDexWeaver { parses++ }

        assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(ByteArray(0)))
        assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(ByteArray(111)))
        assertEquals(0, parses)

        assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(ByteArray(112)))
        assertEquals(1, parses)
        assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(unrelatedDex()))
        assertEquals(2, parses)
    }

    @Test
    fun exactOwnerOrderCallCountsAndAccessFlagsAreRequired() {
        val weaver = DexlibChatBubblesDexWeaver()
        val unsupported = listOf(
            fixtureDex(callOwner = "bind"),
            fixtureDex(reverseCalls = true),
            fixtureDex(duplicateAccessoriesCall = true),
            fixtureDex(messageViewFinal = false),
            fixtureDex(originalAccessFlags = ACC_PRIVATE),
            fixtureDex(setMessageAccessFlags = ACC_PUBLIC),
        )

        unsupported.forEach { dex ->
            assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(dex))
            assertThrows(IOException::class.java) { weaver.rewrite(dex) }
        }
    }

    @Test
    fun rewriteRefusesAnAlreadyPatchedDex() {
        val weaver = DexlibChatBubblesDexWeaver()
        val rewritten = weaver.rewrite(fixtureDex())

        assertEquals(ChatBubblesDexStatus.PATCHED, weaver.inspect(rewritten))
        assertThrows(IOException::class.java) { weaver.rewrite(rewritten) }
    }

    @Test
    fun verificationRejectsAnyUnallowlistedExistingInstructionChange() {
        val weaver = DexlibChatBubblesDexWeaver()
        val original = fixtureDex(untouchedLiteral = 1)
        val changedCandidate = weaver.rewrite(fixtureDex(untouchedLiteral = 2))

        assertThrows(IOException::class.java) {
            weaver.verify(original, changedCandidate)
        }
    }

    @Test
    fun inspectAndVerificationRejectAnInexactHelperBody() {
        val weaver = DexlibChatBubblesDexWeaver()
        val original = fixtureDex()
        val rewritten = weaver.rewrite(original)
        val tampered = replaceAccessoriesAfterCallWithBefore(rewritten)

        assertEquals(ChatBubblesDexStatus.UNSUPPORTED, weaver.inspect(tampered))
        assertThrows(IOException::class.java) {
            weaver.verify(original, tampered)
        }
    }

    @Test
    fun privatelySuppliedHostContainsOneExactlyRewritableChatBubblesDex() {
        val hostPath = System.getenv("THUNDER_PRIVATE_HOST_BASE")
        assumeTrue("No private host was supplied", !hostPath.isNullOrBlank())
        val host = File(requireNotNull(hostPath))
        assertTrue(host.isFile)

        val weaver = DexlibChatBubblesDexWeaver()
        val candidates = ZipFile(host).use { archive ->
            archive.entries().asSequence()
                .filter { entry -> entry.name.matches(Regex("classes(?:[2-9][0-9]*)?\\.dex")) }
                .map { entry ->
                    entry.name to archive.getInputStream(entry).use { it.readBytes() }
                }
                .map { (name, dex) -> Triple(name, dex, weaver.inspect(dex)) }
                .filter { (_, _, status) -> status != ChatBubblesDexStatus.UNSUPPORTED }
                .toList()
        }
        assertEquals(
            candidates.joinToString { (name, _, status) -> "$name=$status" },
            1,
            candidates.size,
        )
        val (_, dex, status) = candidates.single()
        assertEquals(ChatBubblesDexStatus.UNPATCHED, status)
        val rewritten = weaver.rewrite(dex)
        assertEquals(ChatBubblesDexStatus.PATCHED, weaver.inspect(rewritten))
        weaver.verify(dex, rewritten)
    }

    private fun fixtureDex(
        rangeCalls: Boolean = false,
        callOwner: String = "setMessage",
        reverseCalls: Boolean = false,
        duplicateAccessoriesCall: Boolean = false,
        messageViewFinal: Boolean = true,
        originalAccessFlags: Int = ACC_PRIVATE_FINAL,
        setMessageAccessFlags: Int = ACC_PUBLIC_FINAL,
        untouchedLiteral: Int = 1,
    ): ByteArray {
        val accessories = methodReference("configureAccessoriesMargin", listOf(LIST))
        val author = methodReference(
            "configureAuthor",
            listOf(MESSAGE, CHAT_EVENT_HANDLER, CHAIN_PART, MESSAGE_CONTEXT),
        )
        val directMethods = mutableListOf(
            hostMethod(
                "configureAccessoriesMargin",
                listOf(LIST),
                originalAccessFlags,
                registerCount = 2,
                instructions = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            ),
            hostMethod(
                "configureAuthor",
                listOf(MESSAGE, CHAT_EVENT_HANDLER, CHAIN_PART, MESSAGE_CONTEXT),
                originalAccessFlags,
                registerCount = 5,
                instructions = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            ),
        )
        val seamCalls = buildList {
            val first = invokeOriginal(accessories, 2, rangeCalls)
            val second = invokeOriginal(author, 5, rangeCalls)
            if (reverseCalls) {
                add(second)
                add(first)
            } else {
                add(first)
                add(second)
            }
            if (duplicateAccessoriesCall) add(invokeOriginal(accessories, 2, rangeCalls))
            add(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }
        val setMessageInstructions = if (callOwner == "setMessage") {
            seamCalls
        } else {
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }
        if (callOwner != "setMessage") {
            directMethods += hostMethod(
                callOwner,
                emptyList(),
                ACC_PRIVATE_FINAL,
                registerCount = LIVE_SET_MESSAGE_REGISTERS,
                instructions = seamCalls,
            )
        }
        val setMessage = hostMethod(
            "setMessage",
            SET_MESSAGE_PARAMETERS,
            setMessageAccessFlags,
            registerCount = LIVE_SET_MESSAGE_REGISTERS,
            instructions = setMessageInstructions,
        )
        val messageView = ImmutableClassDef(
            MESSAGE_VIEW,
            ACC_PUBLIC or if (messageViewFinal) ACC_FINAL else 0,
            "Ljava/lang/Object;",
            emptyList(),
            "MessageView.kt",
            emptySet(),
            emptyList(),
            emptyList(),
            directMethods,
            listOf(setMessage),
        )
        val untouched = untouchedClass(untouchedLiteral)
        return writeDex(ImmutableDexFile(Opcodes.getDefault(), listOf(messageView, untouched)))
    }

    private fun unrelatedDex(): ByteArray = writeDex(
        ImmutableDexFile(Opcodes.getDefault(), listOf(untouchedClass(1))),
    )

    private fun untouchedClass(literal: Int) = ImmutableClassDef(
        "Lfixture/Untouched;",
        ACC_PUBLIC,
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
                ACC_PUBLIC_STATIC,
                emptySet(),
                emptySet(),
                ImmutableMethodImplementation(
                    1,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, literal),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    emptyList(),
                    emptyList(),
                ),
            ),
        ),
    )

    private fun hostMethod(
        name: String,
        parameterTypes: List<String>,
        accessFlags: Int,
        registerCount: Int,
        instructions: List<Instruction>,
    ) = ImmutableMethod(
        MESSAGE_VIEW,
        name,
        parameterTypes.map { ImmutableMethodParameter(it, emptySet(), null) },
        "V",
        accessFlags,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(registerCount, instructions, emptyList(), emptyList()),
    )

    private fun invokeOriginal(
        reference: MethodReference,
        registerCount: Int,
        range: Boolean,
    ): Instruction = if (range) {
        ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 0, registerCount, reference)
    } else {
        ImmutableInstruction35c(
            Opcode.INVOKE_DIRECT,
            registerCount,
            0,
            1,
            2,
            3,
            4,
            reference,
        )
    }

    private fun methodReference(name: String, parameters: List<String>) =
        ImmutableMethodReference(MESSAGE_VIEW, name, parameters, "V")

    private fun setMessageCalls(dex: DexBackedDexFile): List<Instruction> {
        val messageView = dex.classes.single { it.type == MESSAGE_VIEW }
        val setMessage = messageView.methods.single { method ->
            method.name == "setMessage" &&
                method.parameterTypes.map(CharSequence::toString) == SET_MESSAGE_PARAMETERS
        }
        return setMessage.implementation!!.instructions.filter { instruction ->
            val reference = invocationReference(instruction) ?: return@filter false
            reference.name in setOf(
                "configureAccessoriesMargin",
                "configureAuthor",
                "thunder\$configureAccessoriesMargin",
                "thunder\$configureAuthor",
            )
        }.toList()
    }

    private fun assertExactHelper(
        messageView: ClassDef,
        helperName: String,
        originalName: String,
        beforeName: String,
        afterName: String,
    ) {
        val helper = messageView.directMethods.single { it.name == helperName }
        assertEquals(ACC_PRIVATE_STATIC_SYNTHETIC, helper.accessFlags)
        assertTrue(helper.annotations.isEmpty())
        assertTrue(helper.hiddenApiRestrictions.isEmpty())
        val implementation = helper.implementation!!
        assertEquals(helper.parameterTypes.size, implementation.registerCount)
        assertTrue(implementation.tryBlocks.none())
        assertTrue(implementation.debugItems.none())

        val instructions = implementation.instructions.toList()
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
            instructions.map { it.opcode },
        )
        assertEquals(
            listOf(beforeName, originalName, afterName),
            instructions.take(3).map { invocationReference(it)!!.name },
        )
        assertEquals(
            listOf(BRIDGE, MESSAGE_VIEW, BRIDGE),
            instructions.take(3).map { invocationReference(it)!!.definingClass },
        )
        assertFalse(
            instructions.mapNotNull(::invocationReference).any {
                it.definingClass.startsWith("Ljava/lang/reflect/")
            },
        )
    }

    private fun replaceAccessoriesAfterCallWithBefore(dex: ByteArray): ByteArray {
        val source = parse(dex)
        val beforeReference = ImmutableMethodReference(
            BRIDGE,
            "thunderBeforeConfigureAccessoriesMargin",
            listOf("Ljava/lang/Object;"),
            "V",
        )
        val classes = source.classes.map { classDef ->
            if (classDef.type != MESSAGE_VIEW) return@map classDef
            val directMethods = classDef.directMethods.map { method ->
                if (method.name != "thunder\$configureAccessoriesMargin") return@map method
                val implementation = method.implementation!!
                val instructions = implementation.instructions.mapIndexed { index, instruction ->
                    if (index != 2) {
                        ImmutableInstruction.of(instruction)
                    } else {
                        val call = instruction as Instruction35c
                        ImmutableInstruction35c(
                            call.opcode,
                            call.registerCount,
                            call.registerC,
                            call.registerD,
                            call.registerE,
                            call.registerF,
                            call.registerG,
                            beforeReference,
                        )
                    }
                }
                ImmutableMethod(
                    method.definingClass,
                    method.name,
                    method.parameters,
                    method.returnType,
                    method.accessFlags,
                    method.annotations,
                    method.hiddenApiRestrictions,
                    ImmutableMethodImplementation(
                        implementation.registerCount,
                        instructions,
                        implementation.tryBlocks,
                        implementation.debugItems,
                    ),
                )
            }
            ImmutableClassDef(
                classDef.type,
                classDef.accessFlags,
                classDef.superclass,
                classDef.interfaces,
                classDef.sourceFile,
                classDef.annotations,
                classDef.staticFields,
                classDef.instanceFields,
                directMethods,
                classDef.virtualMethods,
            )
        }.toSet()
        return writeDex(object : DexFile {
            override fun getClasses(): Set<ClassDef> = classes
            override fun getOpcodes(): Opcodes = source.opcodes
        })
    }

    private fun registerShape(instruction: Instruction): List<Int> = when (instruction) {
        is Instruction35c -> listOf(
            instruction.registerCount,
            instruction.registerC,
            instruction.registerD,
            instruction.registerE,
            instruction.registerF,
            instruction.registerG,
        )
        is Instruction3rc -> listOf(instruction.registerCount, instruction.startRegister)
        else -> error("Unexpected invoke format")
    }

    private fun invocationReference(instruction: Instruction): MethodReference? = when (instruction) {
        is Instruction35c -> instruction.reference as? MethodReference
        is Instruction3rc -> instruction.reference as? MethodReference
        else -> null
    }

    private fun parse(bytes: ByteArray) = DexBackedDexFile(Opcodes.getDefault(), bytes)

    private fun writeDex(dex: DexFile): ByteArray {
        val output = MemoryDataStore()
        return try {
            DexPool.writeTo(output, dex)
            output.data
        } finally {
            output.close()
        }
    }

    private companion object {
        const val ACC_PUBLIC = 0x1
        const val ACC_PRIVATE = 0x2
        const val ACC_STATIC = 0x8
        const val ACC_FINAL = 0x10
        const val ACC_SYNTHETIC = 0x1000
        const val ACC_PRIVATE_FINAL = ACC_PRIVATE or ACC_FINAL
        const val ACC_PUBLIC_FINAL = ACC_PUBLIC or ACC_FINAL
        const val ACC_PUBLIC_STATIC = ACC_PUBLIC or ACC_STATIC
        const val ACC_PRIVATE_STATIC_SYNTHETIC = ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC
        const val LIVE_SET_MESSAGE_REGISTERS = 24

        const val MESSAGE_VIEW = "Lcom/discord/chat/presentation/message/MessageView;"
        const val MESSAGE = "Lcom/discord/chat/bridge/Message;"
        const val LIST = "Ljava/util/List;"
        const val CHAT_EVENT_HANDLER = "Lcom/discord/chat/presentation/events/ChatEventHandler;"
        const val CHAIN_PART = "Lcom/discord/chat/presentation/message/MessageView\$ChainPart;"
        const val MESSAGE_CONTEXT = "Lcom/discord/chat/presentation/root/MessageContext;"
        const val COMPONENT_PROVIDER =
            "Lcom/discord/chat/presentation/message/view/botuikit/ComponentProvider;"
        const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        const val BRIDGE = "Ldev/thunder/bootstrap/ThunderChatBubblesBridge;"

        val SET_MESSAGE_PARAMETERS = listOf(
            MESSAGE,
            MESSAGE_CONTEXT,
            CHAT_EVENT_HANDLER,
            COMPONENT_PROVIDER,
            FUNCTION0,
            "Z",
            "Z",
        )
    }
}
