package dev.thunder.injection.custom

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FieldOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.InlineIndexInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.VariableRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.VerificationErrorInstruction
import com.android.tools.smali.dexlib2.iface.instruction.VtableIndexInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.IOException

internal interface ChatBubblesDexWeaver {
    /**
     * Classifies one bounded host DEX. Unsupported and malformed host files are normal while
     * scanning a multidex APK, so this method deliberately does not throw for either case.
     */
    fun inspect(dex: ByteArray): ChatBubblesDexStatus

    /** Produces a rewrite candidate. The backend must call [verify] before signing the APK. */
    fun rewrite(dex: ByteArray): ByteArray

    fun verify(original: ByteArray, rewritten: ByteArray)
}

internal enum class ChatBubblesDexStatus {
    UNPATCHED,
    PATCHED,
    UNSUPPORTED,
}

internal class DexlibChatBubblesDexWeaver(
    private val parseObserver: () -> Unit = {},
) : ChatBubblesDexWeaver {
    private val seams = listOf(
        Seam(
            original = ImmutableMethodReference(
                MESSAGE_VIEW,
                "configureAccessoriesMargin",
                listOf(LIST),
                VOID,
            ),
            helper = ImmutableMethodReference(
                MESSAGE_VIEW,
                "thunder\$configureAccessoriesMargin",
                listOf(MESSAGE_VIEW, LIST),
                VOID,
            ),
            before = bridgeReference("thunderBeforeConfigureAccessoriesMargin"),
            after = bridgeReference("thunderAfterConfigureAccessoriesMargin"),
        ),
        Seam(
            original = ImmutableMethodReference(
                MESSAGE_VIEW,
                "configureAuthor",
                listOf(MESSAGE, CHAT_EVENT_HANDLER, CHAIN_PART, MESSAGE_CONTEXT),
                VOID,
            ),
            helper = ImmutableMethodReference(
                MESSAGE_VIEW,
                "thunder\$configureAuthor",
                listOf(MESSAGE_VIEW, MESSAGE, CHAT_EVENT_HANDLER, CHAIN_PART, MESSAGE_CONTEXT),
                VOID,
            ),
            before = bridgeReference("thunderBeforeConfigureAuthor"),
            after = bridgeReference("thunderAfterConfigureAuthor"),
        ),
    )

    override fun inspect(dex: ByteArray): ChatBubblesDexStatus = try {
        classify(parse(dex))
    } catch (_: Exception) {
        ChatBubblesDexStatus.UNSUPPORTED
    }

    override fun rewrite(dex: ByteArray): ByteArray {
        val source = parse(dex)
        val status = classify(source)
        if (status != ChatBubblesDexStatus.UNPATCHED) {
            throw IOException("ChatBubbles DEX seam is not exactly compatible: $status")
        }

        val replacements = IntArray(seams.size)
        val classes = source.classes.mapTo(LinkedHashSet(source.classes.size)) { classDef ->
            if (classDef.type == MESSAGE_VIEW) rewriteMessageView(classDef, replacements) else classDef
        }
        if (replacements.any { it != EXPECTED_CALLS_PER_SEAM }) {
            throw IOException("ChatBubbles DEX rewrite did not replace exactly one call per seam")
        }

        val output = MemoryDataStore(dex.size + HELPER_SIZE_ALLOWANCE)
        val rewritten = try {
            DexPool.writeTo(output, object : DexFile {
                override fun getClasses(): Set<ClassDef> = classes
                override fun getOpcodes(): Opcodes = source.opcodes
            })
            output.data
        } catch (error: RuntimeException) {
            throw IOException("ChatBubbles DEX could not be serialized", error)
        } finally {
            output.close()
        }
        if (rewritten.size !in MIN_DEX_BYTES..MAX_DEX_BYTES) {
            throw IOException("Rewritten ChatBubbles DEX is outside its size bound")
        }
        return rewritten
    }

    override fun verify(original: ByteArray, rewritten: ByteArray) {
        val before = parse(original)
        val after = parse(rewritten)
        if (classify(before) != ChatBubblesDexStatus.UNPATCHED) {
            throw IOException("Original ChatBubbles DEX is not the exact unpatched seam")
        }
        if (classify(after) != ChatBubblesDexStatus.PATCHED) {
            throw IOException("Rewritten ChatBubbles DEX does not contain the exact patched seam")
        }

        val beforeClasses = before.classes.associateBy { it.type }
        val afterClasses = after.classes.associateBy { it.type }
        if (beforeClasses.keys != afterClasses.keys) throw IOException("ChatBubbles DEX class set changed")

        val replacementCounts = IntArray(seams.size)
        for ((type, inputClass) in beforeClasses) {
            val outputClass = afterClasses.getValue(type)
            verifyClassShape(inputClass, outputClass)
            verifyMethodSets(inputClass, outputClass)

            val inputMethods = inputClass.methods.associateBy(::methodKey)
            val outputMethods = outputClass.methods.associateBy(::methodKey)
            for ((key, inputMethod) in inputMethods) {
                val outputMethod = outputMethods[key]
                    ?: throw IOException("Existing method disappeared from $type: $key")
                verifyMethodShape(inputMethod, outputMethod)

                val inputInstructions = inputMethod.implementation?.instructions?.toList().orEmpty()
                val outputInstructions = outputMethod.implementation?.instructions?.toList().orEmpty()
                if (inputInstructions.size != outputInstructions.size) {
                    throw IOException("Instruction count changed in $type->$key")
                }
                for (index in inputInstructions.indices) {
                    val inputInstruction = inputInstructions[index]
                    val outputInstruction = outputInstructions[index]
                    val seamIndex = seamIndexForOriginal(inputInstruction)
                    if (seamIndex >= 0) {
                        verifyReplacement(inputInstruction, outputInstruction, seams[seamIndex])
                        replacementCounts[seamIndex]++
                    } else if (instructionShape(inputInstruction) != instructionShape(outputInstruction)) {
                        throw IOException(
                            "Unallowlisted instruction changed in $type->$key at $index " +
                                "(${inputInstruction.opcode.name}/${inputInstruction.codeUnits} -> " +
                                "${outputInstruction.opcode.name}/${outputInstruction.codeUnits}; " +
                                "instructions=${inputInstructions.size}, " +
                                "offsets=${inputInstructions.count { it is OffsetInstruction }}, " +
                                "tries=${inputMethod.implementation?.tryBlocks?.count() ?: 0}, " +
                                "debugAfterZero=${inputMethod.implementation?.debugItems?.count { it.codeAddress > 0 } ?: 0})",
                        )
                    }
                }
            }
        }
        if (replacementCounts.any { it != EXPECTED_CALLS_PER_SEAM }) {
            throw IOException("ChatBubbles DEX replacement count is invalid")
        }

        val outputMessageView = afterClasses.getValue(MESSAGE_VIEW)
        for (seam in seams) {
            val helper = outputMessageView.methods.singleOrNull { methodKey(it) == methodReference(seam.helper) }
                ?: throw IOException("ChatBubbles helper is missing or duplicated: " + methodReference(seam.helper))
            if (!isExactHelper(helper, seam)) {
                throw IOException("ChatBubbles helper body is not exact: " + methodReference(seam.helper))
            }
        }
    }

    private fun parse(bytes: ByteArray): DexBackedDexFile {
        if (bytes.size !in MIN_DEX_BYTES..MAX_DEX_BYTES) throw IOException("Host DEX is outside its size bound")
        parseObserver()
        return try {
            DexBackedDexFile(Opcodes.getDefault(), bytes)
        } catch (error: RuntimeException) {
            throw IOException("Host DEX cannot be parsed", error)
        }
    }

    private fun classify(dex: DexBackedDexFile): ChatBubblesDexStatus {
        val targetClasses = dex.classes.filter { it.type == MESSAGE_VIEW }
        if (targetClasses.size != 1) return ChatBubblesDexStatus.UNSUPPORTED
        val target = targetClasses.single()
        if (!isCompatibleMessageView(target)) return ChatBubblesDexStatus.UNSUPPORTED

        val methods = target.methods.toList()
        val setMessageMethods = methods.filter { method ->
            methodKey(method) == methodReference(setMessageReference)
        }
        if (setMessageMethods.size != 1 || !isCompatibleSetMessage(setMessageMethods.single())) {
            return ChatBubblesDexStatus.UNSUPPORTED
        }
        val originals = seams.map { seam ->
            methods.filter { method -> methodKey(method) == methodReference(seam.original) }
        }
        if (originals.any { it.size != 1 } ||
            originals.flatten().any { !isCompatibleOriginalMethod(it) }
        ) return ChatBubblesDexStatus.UNSUPPORTED

        val helpers = seams.map { seam ->
            methods.filter { method -> methodKey(method) == methodReference(seam.helper) }
        }
        if (helpers.any { it.size > 1 }) return ChatBubblesDexStatus.UNSUPPORTED

        val trackedReferences = seams.flatMap { seam ->
            listOf(seam.original, seam.helper, seam.before, seam.after)
        }.associateBy(::methodReference)
        val calls = HashMap<String, MutableList<CallSite>>()
        for (classDef in dex.classes) {
            for (method in classDef.methods) {
                method.implementation?.instructions?.forEachIndexed { index, instruction ->
                    val reference = invocationReference(instruction) ?: return@forEachIndexed
                    val key = methodReference(reference)
                    if (key in trackedReferences) {
                        calls.getOrPut(key, ::ArrayList).add(
                            CallSite(classDef.type, methodKey(method), index, instruction),
                        )
                    }
                }
            }
        }

        return when {
            helpers.all { it.isEmpty() } && isExactUnpatched(calls) -> ChatBubblesDexStatus.UNPATCHED
            helpers.all { it.size == 1 } &&
                seams.indices.all { index -> isExactHelper(helpers[index].single(), seams[index]) } &&
                isExactPatched(calls) -> ChatBubblesDexStatus.PATCHED
            else -> ChatBubblesDexStatus.UNSUPPORTED
        }
    }

    private fun isCompatibleMessageView(classDef: ClassDef): Boolean =
        classDef.accessFlags and ACC_FINAL != 0 &&
            classDef.accessFlags and (ACC_INTERFACE or ACC_ABSTRACT) == 0

    private fun isCompatibleOriginalMethod(method: Method): Boolean =
        method.accessFlags and ACC_PRIVATE != 0 &&
            method.accessFlags and ACC_FINAL != 0 &&
            method.accessFlags and (ACC_STATIC or ACC_ABSTRACT or ACC_NATIVE) == 0 &&
            method.implementation != null &&
            method.definingClass == MESSAGE_VIEW

    private fun isCompatibleSetMessage(method: Method): Boolean =
        method.accessFlags and ACC_PUBLIC != 0 &&
            method.accessFlags and ACC_FINAL != 0 &&
            method.accessFlags and (ACC_STATIC or ACC_ABSTRACT or ACC_NATIVE) == 0 &&
            method.implementation != null &&
            method.definingClass == MESSAGE_VIEW

    private fun isExactUnpatched(calls: Map<String, List<CallSite>>): Boolean {
        val exactCalls = seams.all { seam ->
            val originalCalls = calls[methodReference(seam.original)].orEmpty()
            val helperCalls = calls[methodReference(seam.helper)].orEmpty()
            val beforeCalls = calls[methodReference(seam.before)].orEmpty()
            val afterCalls = calls[methodReference(seam.after)].orEmpty()
            originalCalls.size == EXPECTED_CALLS_PER_SEAM &&
                isCompatibleInvoke(originalCalls.single(), seam.original, InvokeKind.DIRECT) &&
                originalCalls.single().ownerMethod == methodReference(setMessageReference) &&
                helperCalls.isEmpty() &&
                beforeCalls.isEmpty() &&
                afterCalls.isEmpty()
        }
        return exactCalls && callsAppearInSeamOrder(calls) { it.original }
    }

    private fun isExactPatched(calls: Map<String, List<CallSite>>): Boolean {
        val exactCalls = seams.all { seam ->
            val originalCalls = calls[methodReference(seam.original)].orEmpty()
            val helperCalls = calls[methodReference(seam.helper)].orEmpty()
            val beforeCalls = calls[methodReference(seam.before)].orEmpty()
            val afterCalls = calls[methodReference(seam.after)].orEmpty()
            val helperKey = methodReference(seam.helper)
            originalCalls.size == EXPECTED_CALLS_PER_SEAM &&
                isCompatibleInvoke(originalCalls.single(), seam.original, InvokeKind.DIRECT) &&
                originalCalls.single().ownerMethod == helperKey &&
                helperCalls.size == EXPECTED_CALLS_PER_SEAM &&
                isCompatibleInvoke(helperCalls.single(), seam.helper, InvokeKind.STATIC) &&
                helperCalls.single().ownerMethod == methodReference(setMessageReference) &&
                beforeCalls.size == EXPECTED_CALLS_PER_SEAM &&
                isCompatibleInvoke(beforeCalls.single(), seam.before, InvokeKind.STATIC) &&
                beforeCalls.single().ownerMethod == helperKey &&
                afterCalls.size == EXPECTED_CALLS_PER_SEAM &&
                isCompatibleInvoke(afterCalls.single(), seam.after, InvokeKind.STATIC) &&
                afterCalls.single().ownerMethod == helperKey
        }
        return exactCalls && callsAppearInSeamOrder(calls) { it.helper }
    }

    private fun callsAppearInSeamOrder(
        calls: Map<String, List<CallSite>>,
        reference: (Seam) -> MethodReference,
    ): Boolean {
        val orderedSites = seams.map { seam ->
            calls[methodReference(reference(seam))]?.singleOrNull() ?: return false
        }
        return orderedSites.zipWithNext().all { (first, second) ->
            first.ownerClass == second.ownerClass &&
                first.ownerMethod == second.ownerMethod &&
                first.instructionIndex < second.instructionIndex
        }
    }

    private fun isCompatibleInvoke(
        call: CallSite,
        expected: MethodReference,
        kind: InvokeKind,
    ): Boolean {
        if (call.ownerClass != MESSAGE_VIEW ||
            methodReference(invocationReference(call.instruction) ?: return false) != methodReference(expected)
        ) return false
        val expectedRegisters = expected.parameterTypes.sumOf(::registerWidth) +
            if (kind == InvokeKind.DIRECT) 1 else 0
        return when (val instruction = call.instruction) {
            is Instruction35c -> instruction.opcode == kind.normalOpcode &&
                instruction.registerCount == expectedRegisters
            is Instruction3rc -> instruction.opcode == kind.rangeOpcode &&
                instruction.registerCount == expectedRegisters
            else -> false
        }
    }

    private fun rewriteMessageView(classDef: ClassDef, replacements: IntArray): ClassDef {
        val directMethods = classDef.directMethods.map { method -> rewriteMethod(method, replacements) } +
            seams.map(::helperMethod)
        val virtualMethods = classDef.virtualMethods.map { method -> rewriteMethod(method, replacements) }
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            directMethods,
            virtualMethods,
        )
    }

    private fun rewriteMethod(method: Method, replacements: IntArray): Method {
        val implementation = method.implementation ?: return method
        if (implementation.instructions.none { seamIndexForOriginal(it) >= 0 }) return method
        val instructions = implementation.instructions.map { instruction ->
            val seamIndex = seamIndexForOriginal(instruction)
            if (seamIndex < 0) {
                ImmutableInstruction.of(instruction)
            } else {
                replacements[seamIndex]++
                replacement(instruction, seams[seamIndex])
            }
        }
        return ImmutableMethod(
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

    private fun replacement(instruction: Instruction, seam: Seam): ImmutableInstruction = when (instruction) {
        is Instruction35c -> ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            instruction.registerCount,
            instruction.registerC,
            instruction.registerD,
            instruction.registerE,
            instruction.registerF,
            instruction.registerG,
            seam.helper,
        )
        is Instruction3rc -> ImmutableInstruction3rc(
            Opcode.INVOKE_STATIC_RANGE,
            instruction.startRegister,
            instruction.registerCount,
            seam.helper,
        )
        else -> throw IOException("ChatBubbles seam uses an unsupported invoke format")
    }

    private fun helperMethod(seam: Seam): ImmutableMethod {
        val parameterTypes = seam.helper.parameterTypes.map(CharSequence::toString)
        val parameters = parameterTypes.map { type ->
            ImmutableMethodParameter(type, emptySet(), null)
        }
        val originalRegisters = IntArray(parameterTypes.size) { it }
        val instructions = listOf(
            invoke35c(Opcode.INVOKE_STATIC, intArrayOf(0), seam.before),
            invoke35c(Opcode.INVOKE_DIRECT, originalRegisters, seam.original),
            invoke35c(Opcode.INVOKE_STATIC, intArrayOf(0), seam.after),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            MESSAGE_VIEW,
            seam.helper.name,
            parameters,
            VOID,
            HELPER_ACCESS_FLAGS,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(
                parameterTypes.sumOf(::registerWidth),
                instructions,
                emptyList(),
                emptyList(),
            ),
        )
    }

    /*
     * The bridge entry points contain their own failure containment. Keep these helpers linear:
     * before, the host's private implementation, then normal-completion after. If the host call
     * throws, binding already aborts and the original exception is preserved without a new handler.
     */
    private fun isExactHelper(method: Method, seam: Seam): Boolean {
        val expected = helperMethod(seam)
        val implementation = method.implementation ?: return false
        val expectedImplementation = expected.implementation ?: return false
        return methodKey(method) == methodReference(seam.helper) &&
            method.accessFlags == HELPER_ACCESS_FLAGS &&
            method.annotations.isEmpty() &&
            method.hiddenApiRestrictions.isEmpty() &&
            method.parameters.map { ParameterShape(it.type, it.name, it.annotations) } ==
            expected.parameters.map { ParameterShape(it.type, it.name, it.annotations) } &&
            implementation.registerCount == expectedImplementation.registerCount &&
            implementation.tryBlocks.none() &&
            implementation.debugItems.none() &&
            implementation.instructions.map(::instructionShape).toList() ==
            expectedImplementation.instructions.map(::instructionShape).toList()
    }

    private fun invoke35c(
        opcode: Opcode,
        registers: IntArray,
        reference: MethodReference,
    ): ImmutableInstruction35c {
        if (registers.size > MAX_35C_REGISTERS || registers.any { it !in 0..MAX_NIBBLE_REGISTER }) {
            throw IOException("ChatBubbles helper registers cannot be encoded")
        }
        return ImmutableInstruction35c(
            opcode,
            registers.size,
            registers.getOrElse(0) { 0 },
            registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 },
            registers.getOrElse(3) { 0 },
            registers.getOrElse(4) { 0 },
            reference,
        )
    }

    private fun seamIndexForOriginal(instruction: Instruction): Int {
        if (instruction.opcode != Opcode.INVOKE_DIRECT &&
            instruction.opcode != Opcode.INVOKE_DIRECT_RANGE
        ) return -1
        val reference = invocationReference(instruction) ?: return -1
        val key = methodReference(reference)
        return seams.indexOfFirst { methodReference(it.original) == key }
    }

    private fun invocationReference(instruction: Instruction): MethodReference? = when (instruction) {
        is Instruction35c -> instruction.reference as? MethodReference
        is Instruction3rc -> instruction.reference as? MethodReference
        else -> null
    }

    private fun verifyReplacement(
        original: Instruction,
        rewritten: Instruction,
        seam: Seam,
    ) {
        val rewrittenReference = invocationReference(rewritten)
        if (rewrittenReference == null ||
            methodReference(rewrittenReference) != methodReference(seam.helper) ||
            original.codeUnits != rewritten.codeUnits
        ) throw IOException("ChatBubbles call replacement changed instruction width or target")
        when {
            original is Instruction35c && rewritten is Instruction35c -> {
                if (original.opcode != Opcode.INVOKE_DIRECT ||
                    rewritten.opcode != Opcode.INVOKE_STATIC ||
                    original.registerCount != rewritten.registerCount ||
                    original.registerC != rewritten.registerC ||
                    original.registerD != rewritten.registerD ||
                    original.registerE != rewritten.registerE ||
                    original.registerF != rewritten.registerF ||
                    original.registerG != rewritten.registerG
                ) throw IOException("ChatBubbles call replacement changed compact-call registers")
            }
            original is Instruction3rc && rewritten is Instruction3rc -> {
                if (original.opcode != Opcode.INVOKE_DIRECT_RANGE ||
                    rewritten.opcode != Opcode.INVOKE_STATIC_RANGE ||
                    original.startRegister != rewritten.startRegister ||
                    original.registerCount != rewritten.registerCount
                ) throw IOException("ChatBubbles call replacement changed range-call registers")
            }
            else -> throw IOException("ChatBubbles call replacement changed instruction format")
        }
    }

    private fun verifyClassShape(input: ClassDef, output: ClassDef) {
        if (input.accessFlags != output.accessFlags ||
            input.superclass != output.superclass ||
            input.interfaces != output.interfaces ||
            input.sourceFile != output.sourceFile ||
            input.annotations != output.annotations
        ) throw IOException("ChatBubbles DEX class metadata changed in " + input.type)

        val inputFields = input.fields.associateBy { it.definingClass + "->" + it.name + ":" + it.type }
        val outputFields = output.fields.associateBy { it.definingClass + "->" + it.name + ":" + it.type }
        if (inputFields.keys != outputFields.keys) {
            throw IOException("ChatBubbles DEX field set changed in " + input.type)
        }
        for ((key, inputField) in inputFields) {
            val outputField = outputFields.getValue(key)
            if (inputField.accessFlags != outputField.accessFlags ||
                inputField.annotations != outputField.annotations ||
                inputField.hiddenApiRestrictions != outputField.hiddenApiRestrictions ||
                !encodedValueEquals(inputField.initialValue, outputField.initialValue, inputField.type)
            ) throw IOException("ChatBubbles DEX field metadata changed in $key")
        }
    }

    private fun verifyMethodSets(input: ClassDef, output: ClassDef) {
        val inputDirect = input.directMethods.map(::methodKey).toSet()
        val outputDirect = output.directMethods.map(::methodKey).toSet()
        val inputVirtual = input.virtualMethods.map(::methodKey).toSet()
        val outputVirtual = output.virtualMethods.map(::methodKey).toSet()
        val expectedDirect = if (input.type == MESSAGE_VIEW) {
            inputDirect + seams.map { methodReference(it.helper) }
        } else {
            inputDirect
        }
        if (outputDirect != expectedDirect || outputVirtual != inputVirtual) {
            throw IOException("ChatBubbles DEX method set changed unexpectedly in " + input.type)
        }
    }

    private fun verifyMethodShape(input: Method, output: Method) {
        val inputImplementation = input.implementation
        val outputImplementation = output.implementation
        try {
            output.parameters.forEach { parameter ->
                parameter.name
                parameter.annotations.size
            }
            outputImplementation?.debugItems?.forEach { item ->
                item.codeAddress
                item.debugItemType
            }
        } catch (error: RuntimeException) {
            throw IOException("ChatBubbles DEX debug metadata is invalid in " + methodKey(output), error)
        }
        if (input.accessFlags != output.accessFlags ||
            input.annotations != output.annotations ||
            input.hiddenApiRestrictions != output.hiddenApiRestrictions ||
            input.parameters.map { ParameterShape(it.type, it.name, it.annotations) } !=
            output.parameters.map { ParameterShape(it.type, it.name, it.annotations) } ||
            (inputImplementation == null) != (outputImplementation == null)
        ) throw IOException("ChatBubbles DEX method metadata changed in " + methodKey(input))
        if (inputImplementation != null && outputImplementation != null &&
            (inputImplementation.registerCount != outputImplementation.registerCount ||
                inputImplementation.tryBlocks.map(::tryBlockShape) !=
                outputImplementation.tryBlocks.map(::tryBlockShape))
        ) throw IOException("ChatBubbles DEX method structure changed in " + methodKey(input))
    }

    private fun methodKey(method: MethodReference): String = methodReference(method)

    private fun methodReference(reference: MethodReference): String =
        reference.definingClass + "->" + reference.name + "(" +
            reference.parameterTypes.joinToString("") + ")" + reference.returnType

    private fun instructionShape(instruction: Instruction): String {
        val values = ArrayList<String>(12)
        when (instruction) {
            is FiveRegisterInstruction -> values +=
                "registers=" + instruction.registerCount + ":" +
                    listOf(
                        instruction.registerC,
                        instruction.registerD,
                        instruction.registerE,
                        instruction.registerF,
                        instruction.registerG,
                    ).joinToString()
            is ThreeRegisterInstruction -> values +=
                "registers=" + instruction.registerA + "," + instruction.registerB + "," + instruction.registerC
            is TwoRegisterInstruction -> values += "registers=" + instruction.registerA + "," + instruction.registerB
            is OneRegisterInstruction -> values += "registers=" + instruction.registerA
        }
        if (instruction is RegisterRangeInstruction) {
            values += "range=" + instruction.startRegister + ":" + instruction.registerCount
        } else if (instruction is VariableRegisterInstruction && instruction !is FiveRegisterInstruction) {
            values += "registerCount=" + instruction.registerCount
        }
        if (instruction is NarrowLiteralInstruction) values += "narrow=" + instruction.narrowLiteral
        else if (instruction is WideLiteralInstruction) values += "wide=" + instruction.wideLiteral
        if (instruction is OffsetInstruction) values += "offset=" + instruction.codeOffset
        if (instruction is DualReferenceInstruction) {
            values += "reference=" + canonical(instruction.reference) + ":" + instruction.referenceType
            values += "reference2=" + canonical(instruction.reference2) + ":" + instruction.referenceType2
        } else if (instruction is ReferenceInstruction) {
            values += "reference=" + canonical(instruction.reference) + ":" + instruction.referenceType
        }
        if (instruction is VerificationErrorInstruction) values += "verification=" + instruction.verificationError
        if (instruction is VtableIndexInstruction) values += "vtable=" + instruction.vtableIndex
        if (instruction is FieldOffsetInstruction) values += "fieldOffset=" + instruction.fieldOffset
        if (instruction is InlineIndexInstruction) values += "inline=" + instruction.inlineIndex
        if (instruction is SwitchPayload) {
            values += "switch=" + instruction.switchElements.joinToString { it.key.toString() + ":" + it.offset }
        }
        if (instruction is ArrayPayload) {
            values += "array=" + instruction.elementWidth + ":" + instruction.arrayElements.joinToString()
        }
        return instruction.opcode.name + ":" + instruction.codeUnits + ":" + values.joinToString("|")
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is MethodReference -> methodReference(value)
        is FieldReference -> value.definingClass + "->" + value.name + ":" + value.type
        is TypeReference -> value.type
        is StringReference -> value.string
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }

    private fun tryBlockShape(block: TryBlock<*>): String =
        block.startCodeAddress.toString() + ":" + block.codeUnitCount + ":" +
            block.exceptionHandlers.joinToString { handler ->
                (handler.exceptionType ?: "*") + "@" + handler.handlerCodeAddress
            }

    private fun encodedValueEquals(
        first: com.android.tools.smali.dexlib2.iface.value.EncodedValue?,
        second: com.android.tools.smali.dexlib2.iface.value.EncodedValue?,
        fieldType: String,
    ): Boolean = when {
        first == null -> second == null || isDefaultValue(second, fieldType)
        second == null -> isDefaultValue(first, fieldType)
        else -> first.compareTo(second) == 0
    }

    private fun isDefaultValue(
        value: com.android.tools.smali.dexlib2.iface.value.EncodedValue,
        fieldType: String,
    ): Boolean = when (fieldType) {
        "Z" -> (value as? com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue)?.value == false
        "B" -> (value as? com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue)?.value == 0.toByte()
        "S" -> (value as? com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue)?.value == 0.toShort()
        "C" -> (value as? com.android.tools.smali.dexlib2.iface.value.CharEncodedValue)?.value == '\u0000'
        "I" -> (value as? com.android.tools.smali.dexlib2.iface.value.IntEncodedValue)?.value == 0
        "J" -> (value as? com.android.tools.smali.dexlib2.iface.value.LongEncodedValue)?.value == 0L
        "F" -> (value as? com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue)?.value == 0f
        "D" -> (value as? com.android.tools.smali.dexlib2.iface.value.DoubleEncodedValue)?.value == 0.0
        else -> (fieldType.startsWith("L") || fieldType.startsWith("[")) &&
            value is com.android.tools.smali.dexlib2.iface.value.NullEncodedValue
    }

    private fun registerWidth(type: CharSequence): Int =
        if (type.toString() == "J" || type.toString() == "D") 2 else 1

    private fun bridgeReference(name: String) = ImmutableMethodReference(
        BRIDGE,
        name,
        listOf(OBJECT),
        VOID,
    )

    private val setMessageReference = ImmutableMethodReference(
        MESSAGE_VIEW,
        "setMessage",
        listOf(
            MESSAGE,
            MESSAGE_CONTEXT,
            CHAT_EVENT_HANDLER,
            COMPONENT_PROVIDER,
            FUNCTION0,
            BOOLEAN,
            BOOLEAN,
        ),
        VOID,
    )

    private data class Seam(
        val original: ImmutableMethodReference,
        val helper: ImmutableMethodReference,
        val before: ImmutableMethodReference,
        val after: ImmutableMethodReference,
    )

    private data class CallSite(
        val ownerClass: String,
        val ownerMethod: String,
        val instructionIndex: Int,
        val instruction: Instruction,
    )

    private data class ParameterShape(
        val type: String,
        val name: String?,
        val annotations: Set<*>,
    )

    private enum class InvokeKind(
        val normalOpcode: Opcode,
        val rangeOpcode: Opcode,
    ) {
        DIRECT(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE),
        STATIC(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE),
    }

    private companion object {
        const val MIN_DEX_BYTES = 112
        const val MAX_DEX_BYTES = 64 * 1024 * 1024
        const val HELPER_SIZE_ALLOWANCE = 128 * 1024
        const val EXPECTED_CALLS_PER_SEAM = 1
        const val MAX_35C_REGISTERS = 5
        const val MAX_NIBBLE_REGISTER = 15

        const val ACC_PUBLIC = 0x1
        const val ACC_PRIVATE = 0x2
        const val ACC_STATIC = 0x8
        const val ACC_FINAL = 0x10
        const val ACC_INTERFACE = 0x200
        const val ACC_ABSTRACT = 0x400
        const val ACC_SYNTHETIC = 0x1000
        const val ACC_NATIVE = 0x100
        const val HELPER_ACCESS_FLAGS = ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC

        const val VOID = "V"
        const val BOOLEAN = "Z"
        const val OBJECT = "Ljava/lang/Object;"
        const val LIST = "Ljava/util/List;"
        const val MESSAGE_VIEW = "Lcom/discord/chat/presentation/message/MessageView;"
        const val MESSAGE = "Lcom/discord/chat/bridge/Message;"
        const val CHAT_EVENT_HANDLER = "Lcom/discord/chat/presentation/events/ChatEventHandler;"
        const val CHAIN_PART = "Lcom/discord/chat/presentation/message/MessageView\$ChainPart;"
        const val MESSAGE_CONTEXT = "Lcom/discord/chat/presentation/root/MessageContext;"
        const val COMPONENT_PROVIDER =
            "Lcom/discord/chat/presentation/message/view/botuikit/ComponentProvider;"
        const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        const val BRIDGE = "Ldev/thunder/bootstrap/ThunderChatBubblesBridge;"
    }
}
