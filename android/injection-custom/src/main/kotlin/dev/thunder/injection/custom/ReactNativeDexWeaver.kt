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
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.IOException

internal interface ReactNativeDexWeaver {
    fun originalCallCount(dex: ByteArray): Int
    fun patchedCallCount(dex: ByteArray): Int
    fun callCounts(dex: ByteArray): ReactNativeCallCounts = ReactNativeCallCounts(
        original = originalCallCount(dex),
        patched = patchedCallCount(dex),
    )
    /** Produces a rewrite candidate. The backend must call [verify] before the APK can be signed. */
    fun rewrite(dex: ByteArray): ByteArray
    fun verify(original: ByteArray, rewritten: ByteArray)
}

internal data class ReactNativeCallCounts(val original: Int, val patched: Int)

internal class DexlibReactNativeDexWeaver(
    private val parseObserver: () -> Unit = {},
) : ReactNativeDexWeaver {
    private val bridgeReference = ImmutableMethodReference(
        BRIDGE,
        "loadJSBundle",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
    )

    override fun originalCallCount(dex: ByteArray): Int = callCounts(parse(dex)).original

    override fun patchedCallCount(dex: ByteArray): Int = callCounts(parse(dex)).patched

    override fun callCounts(dex: ByteArray): ReactNativeCallCounts = callCounts(parse(dex))

    override fun rewrite(dex: ByteArray): ByteArray {
        val source = parse(dex)
        var originals = 0
        val classes = source.classes.mapTo(LinkedHashSet(source.classes.size)) { classDef ->
            rewriteClass(classDef) { originals++ }
        }
        if (originals != EXPECTED_CALLS) throw IOException("React Native seam expected $EXPECTED_CALLS calls but found $originals")
        val output = MemoryDataStore(dex.size + 64 * 1024)
        val rewritten = try {
            DexPool.writeTo(output, object : DexFile {
                override fun getClasses(): Set<ClassDef> = classes
                override fun getOpcodes(): Opcodes = source.opcodes
            })
            output.data
        } finally {
            output.close()
        }
        if (rewritten.size !in 112..MAX_DEX_BYTES) throw IOException("Rewritten React Native DEX is outside its size bound")
        return rewritten
    }

    override fun verify(original: ByteArray, rewritten: ByteArray) {
        val before = parse(original)
        val after = parse(rewritten)
        val beforeClasses = before.classes.associateBy { it.type }
        val afterClasses = after.classes.associateBy { it.type }
        if (beforeClasses.keys != afterClasses.keys) throw IOException("React Native DEX class set changed")

        var replacedCalls = 0
        for ((type, inputClass) in beforeClasses) {
            val outputClass = afterClasses.getValue(type)
            verifyClassShape(inputClass, outputClass)
            val inputMethods = inputClass.methods.associateBy(::methodKey)
            val outputMethods = outputClass.methods.associateBy(::methodKey)
            if (inputMethods.keys != outputMethods.keys) throw IOException("React Native DEX method set changed in $type")
            for ((key, inputMethod) in inputMethods) {
                val outputMethod = outputMethods.getValue(key)
                verifyMethodShape(inputMethod, outputMethod)
                val inputInstructions = inputMethod.implementation?.instructions?.toList().orEmpty()
                val outputInstructions = outputMethod.implementation?.instructions?.toList().orEmpty()
                if (inputInstructions.size != outputInstructions.size) throw IOException("Instruction count changed in $type->$key")
                for (index in inputInstructions.indices) {
                    val inputInstruction = inputInstructions[index]
                    val outputInstruction = outputInstructions[index]
                    if (isOriginalCall(inputInstruction)) {
                        verifyReplacement(inputInstruction, outputInstruction)
                        replacedCalls++
                    } else if (instructionShape(inputInstruction) != instructionShape(outputInstruction)) {
                        throw IOException("Unallowlisted instruction changed in $type->$key at $index")
                    }
                }
            }
        }
        if (replacedCalls != EXPECTED_CALLS) throw IOException("React Native DEX replacement count is invalid")
        val rewrittenCounts = callCounts(after)
        if (rewrittenCounts.original != 0 || rewrittenCounts.patched != EXPECTED_CALLS) {
            throw IOException("React Native DEX seam verification failed")
        }
    }

    private fun parse(bytes: ByteArray): DexBackedDexFile {
        if (bytes.size !in 112..MAX_DEX_BYTES) throw IOException("Host DEX is outside its size bound")
        parseObserver()
        return try {
            DexBackedDexFile(Opcodes.getDefault(), bytes)
        } catch (error: RuntimeException) {
            throw IOException("Host DEX cannot be parsed", error)
        }
    }

    private fun rewriteClass(classDef: ClassDef, onReplacement: () -> Unit): ClassDef {
        if (classDef.methods.none { method -> method.implementation?.instructions?.any(::isOriginalCall) == true }) {
            return classDef
        }
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            classDef.directMethods.map { method -> rewriteMethod(method, onReplacement) },
            classDef.virtualMethods.map { method -> rewriteMethod(method, onReplacement) },
        )
    }

    private fun rewriteMethod(method: Method, onReplacement: () -> Unit): Method {
        val implementation = method.implementation
        if (implementation == null || implementation.instructions.none(::isOriginalCall)) return method
        val instructions = implementation.instructions.map { instruction ->
            if (isOriginalCall(instruction)) {
                onReplacement()
                replacement(instruction)
            } else {
                ImmutableInstruction.of(instruction)
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

    private fun replacement(instruction: Instruction): ImmutableInstruction = when (instruction) {
        is Instruction35c -> {
            if (instruction.registerCount != 2) throw IOException("React Native seam has an unexpected register count")
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                instruction.registerCount,
                instruction.registerC,
                instruction.registerD,
                instruction.registerE,
                instruction.registerF,
                instruction.registerG,
                bridgeReference,
            )
        }
        is Instruction3rc -> {
            if (instruction.registerCount != 2) throw IOException("React Native range seam has an unexpected register count")
            ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, instruction.startRegister, instruction.registerCount, bridgeReference)
        }
        else -> throw IOException("React Native seam uses an unsupported instruction format")
    }

    private fun isOriginalCall(instruction: Instruction): Boolean {
        if (instruction.opcode !in setOf(
                Opcode.INVOKE_DIRECT,
                Opcode.INVOKE_DIRECT_RANGE,
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_VIRTUAL_RANGE,
            )
        ) return false
        val reference = when (instruction) {
            is Instruction35c -> instruction.reference
            is Instruction3rc -> instruction.reference
            else -> return false
        } as? MethodReference ?: return false
        return reference.definingClass == REACT_INSTANCE &&
            reference.name == "loadJSBundle" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(BUNDLE_LOADER) &&
            reference.returnType == "V"
    }

    private fun isBridgeCall(instruction: Instruction): Boolean {
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return false
        val reference = when (instruction) {
            is Instruction35c -> instruction.reference
            is Instruction3rc -> instruction.reference
            else -> return false
        } as? MethodReference ?: return false
        return methodReference(reference) == methodReference(bridgeReference)
    }

    private fun callCounts(dex: DexBackedDexFile): ReactNativeCallCounts {
        var originals = 0
        var patched = 0
        dex.classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                method.implementation?.instructions?.forEach { instruction ->
                    if (isOriginalCall(instruction)) originals++
                    if (isBridgeCall(instruction)) patched++
                }
            }
        }
        return ReactNativeCallCounts(originals, patched)
    }

    private fun verifyReplacement(original: Instruction, rewritten: Instruction) {
        if (!isBridgeCall(rewritten) || original.codeUnits != rewritten.codeUnits) {
            throw IOException("React Native call replacement changed instruction width or target")
        }
        when {
            original is Instruction35c && rewritten is Instruction35c -> {
                if (original.registerCount != rewritten.registerCount ||
                    original.registerC != rewritten.registerC || original.registerD != rewritten.registerD ||
                    original.registerE != rewritten.registerE || original.registerF != rewritten.registerF ||
                    original.registerG != rewritten.registerG
                ) throw IOException("React Native call replacement changed registers")
            }
            original is Instruction3rc && rewritten is Instruction3rc -> {
                if (original.startRegister != rewritten.startRegister || original.registerCount != rewritten.registerCount) {
                    throw IOException("React Native range-call replacement changed registers")
                }
            }
            else -> throw IOException("React Native call replacement changed instruction format")
        }
    }

    private fun verifyClassShape(input: ClassDef, output: ClassDef) {
        if (input.accessFlags != output.accessFlags || input.superclass != output.superclass ||
            input.interfaces != output.interfaces || input.sourceFile != output.sourceFile ||
            input.annotations != output.annotations
        ) throw IOException("React Native DEX class metadata changed in ${input.type}")
        val inputFields = input.fields.associateBy { "${it.definingClass}->${it.name}:${it.type}" }
        val outputFields = output.fields.associateBy { "${it.definingClass}->${it.name}:${it.type}" }
        if (inputFields.keys != outputFields.keys) throw IOException(
            "React Native DEX field set changed in ${input.type}; missing=${(inputFields.keys - outputFields.keys).sorted()}; added=${(outputFields.keys - inputFields.keys).sorted()}",
        )
        for ((key, inputField) in inputFields) {
            val outputField = outputFields.getValue(key)
            val differences = listOfNotNull(
                "access".takeIf { inputField.accessFlags != outputField.accessFlags },
                "annotations".takeIf { inputField.annotations != outputField.annotations },
                "hidden-api".takeIf { inputField.hiddenApiRestrictions != outputField.hiddenApiRestrictions },
                "initial-value".takeIf { !encodedValueEquals(inputField.initialValue, outputField.initialValue, inputField.type) },
            )
            if (differences.isNotEmpty()) throw IOException("React Native DEX field metadata changed in $key (${differences.joinToString()})")
        }
    }

    private fun verifyMethodShape(input: Method, output: Method) {
        val inputImplementation = input.implementation
        val outputImplementation = output.implementation
        try {
            output.parameters.forEach { parameter -> parameter.name }
            outputImplementation?.debugItems?.forEach { item -> item.codeAddress }
        } catch (error: RuntimeException) {
            throw IOException("React Native DEX debug metadata is invalid in ${methodKey(output)}", error)
        }
        if (input.accessFlags != output.accessFlags ||
            input.annotations != output.annotations ||
            input.hiddenApiRestrictions != output.hiddenApiRestrictions ||
            (inputImplementation == null) != (outputImplementation == null)
        ) throw IOException("React Native DEX method metadata changed in ${methodKey(input)}")
        if (inputImplementation != null && outputImplementation != null && (
                inputImplementation.registerCount != outputImplementation.registerCount ||
                    inputImplementation.tryBlocks.map(::tryBlockShape) != outputImplementation.tryBlocks.map(::tryBlockShape)
                )
        ) throw IOException("React Native DEX method structure changed in ${methodKey(input)}")
    }

    private fun methodKey(method: MethodReference): String = methodReference(method)

    private fun methodReference(reference: MethodReference): String =
        "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

    private fun instructionShape(instruction: Instruction): String {
        val values = ArrayList<String>(12)
        when (instruction) {
            is FiveRegisterInstruction -> values += "registers=${instruction.registerCount}:${instruction.registerC},${instruction.registerD},${instruction.registerE},${instruction.registerF},${instruction.registerG}"
            is ThreeRegisterInstruction -> values += "registers=${instruction.registerA},${instruction.registerB},${instruction.registerC}"
            is TwoRegisterInstruction -> values += "registers=${instruction.registerA},${instruction.registerB}"
            is OneRegisterInstruction -> values += "registers=${instruction.registerA}"
        }
        if (instruction is RegisterRangeInstruction) values += "range=${instruction.startRegister}:${instruction.registerCount}"
        else if (instruction is VariableRegisterInstruction && instruction !is FiveRegisterInstruction) values += "registerCount=${instruction.registerCount}"
        if (instruction is NarrowLiteralInstruction) values += "narrow=${instruction.narrowLiteral}"
        else if (instruction is WideLiteralInstruction) values += "wide=${instruction.wideLiteral}"
        if (instruction is OffsetInstruction) values += "offset=${instruction.codeOffset}"
        if (instruction is DualReferenceInstruction) {
            values += "reference=${canonical(instruction.reference)}:${instruction.referenceType}"
            values += "reference2=${canonical(instruction.reference2)}:${instruction.referenceType2}"
        } else if (instruction is ReferenceInstruction) {
            values += "reference=${canonical(instruction.reference)}:${instruction.referenceType}"
        }
        if (instruction is VerificationErrorInstruction) values += "verification=${instruction.verificationError}"
        if (instruction is VtableIndexInstruction) values += "vtable=${instruction.vtableIndex}"
        if (instruction is FieldOffsetInstruction) values += "fieldOffset=${instruction.fieldOffset}"
        if (instruction is InlineIndexInstruction) values += "inline=${instruction.inlineIndex}"
        if (instruction is SwitchPayload) values += "switch=${instruction.switchElements.joinToString { "${it.key}:${it.offset}" }}"
        if (instruction is ArrayPayload) values += "array=${instruction.elementWidth}:${instruction.arrayElements.joinToString()}"
        return "${instruction.opcode.name}:${instruction.codeUnits}:${values.joinToString("|")}"
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is MethodReference -> methodReference(value)
        is FieldReference -> "${value.definingClass}->${value.name}:${value.type}"
        is TypeReference -> value.type
        is StringReference -> value.string
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }

    private fun tryBlockShape(block: TryBlock<*>): String =
        "${block.startCodeAddress}:${block.codeUnitCount}:" + block.exceptionHandlers.joinToString { handler ->
            "${handler.exceptionType ?: "*"}@${handler.handlerCodeAddress}"
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

    private companion object {
        const val EXPECTED_CALLS = 2
        const val MAX_DEX_BYTES = 64 * 1024 * 1024
        const val REACT_INSTANCE = "Lcom/facebook/react/runtime/ReactInstance;"
        const val BUNDLE_LOADER = "Lcom/facebook/react/bridge/JSBundleLoader;"
        const val BRIDGE = "Ldev/thunder/bootstrap/ThunderReactNativeBridge;"
    }
}
