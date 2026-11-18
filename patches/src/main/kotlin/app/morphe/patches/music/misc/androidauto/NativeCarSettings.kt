package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val MEDIA_SERVICE = "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"
private const val SETTINGS_SERVICE =
    "Lcom/google/android/apps/youtube/music/mediabrowser/androidautoprojected/AndroidAutoCarAppServices;"
private const val TAB_TYPE = "Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab"
private const val NATIVE = "${TAB_TYPE}\$NativeSettings;"
private const val SOURCE = "${TAB_TYPE}\$Source;"
private const val CONTEXT = "Landroid/content/Context;"
private const val TOGGLE = "Landroidx/car/app/model/Toggle;"
private const val TEMPLATE = "Landroidx/car/app/model/ListTemplate;"

internal fun patchNativeCarSettings() {
    val injection = classDefBy(classDefBy(SETTINGS_SERVICE).superclass!!).methods.single { it.name == "onCreate" }
    val controllerField = injection.instructions.mapNotNull { it.getReference<FieldReference>() }
        .single { it.definingClass == SETTINGS_SERVICE }
    val controller = mutableClassDefBy(controllerField.type)
    exposeNativeSettingsSource(injection, controllerField)

    val screen = Fingerprint(filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = SETTINGS_SERVICE, name = controllerField.name),
        methodCall(name = "<init>", parameters = listOf("L", controller.type))
    )).instructionMatches.last().methodReference.definingClass
    val render = Fingerprint(definingClass = screen, parameters = emptyList(), filters = listOf(
        methodCall(definingClass = TOGGLE, name = "<init>"),
        methodCall(definingClass = TEMPLATE, name = "<init>")
    )).originalMethod
    bindNativeRows(controller, render)
}

/** Both services use the same generated component. Ask for the native controller on each operation. */
private fun exposeNativeSettingsSource(injection: Method, controller: FieldReference) {
    val code = injection.instructions.toList()
    val assign = code.indexOfLast { it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == controller }
    val provider = code.take(assign).last { it.opcode == Opcode.INVOKE_INTERFACE }.getReference<MethodReference>()!!
    val graph = code.subList(0, assign).takeLastWhile {
        it.getReference<TypeReference>()?.type != SETTINGS_SERVICE
    }.filter { it.opcode == Opcode.IGET_OBJECT }.map { it.getReference<FieldReference>()!! }
    val mediaBase = mutableClassDefBy(classDefBy(MEDIA_SERVICE).superclass!!)
    val component = mediaBase.methods.single { it.name == "generatedComponent" && it.parameterTypes.isEmpty() }
    check(mediaBase.methods.single { it.name == "onCreate" }.instructions.any {
        it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == graph.first().definingClass
    }) { "The media and settings services must use the same service component" }
    val readProvider = graph.joinToString("\n") { "iget-object v0, v0, $it" }
    mediaBase.addPatchInterface(SOURCE, "patch_getNativeSettings", """
        invoke-virtual { p0 }, $component
        move-result-object v0
        check-cast v0, ${graph.first().definingClass}
        $readProvider
        invoke-interface { v0 }, $provider
        move-result-object v0
        check-cast v0, ${controller.type}
        return-object v0
    """)
}

private fun bindNativeRows(controller: MutableClass, render: Method) {
    val code = render.instructions.toList()
    val controllerLoad = code.indexOfFirst { it.opcode == Opcode.IGET_OBJECT &&
            it.getReference<FieldReference>()?.type == controller.type }
    val controllerRegister = code[controllerLoad].registersUsed.first()
    val contextRegister = code.take(controllerLoad).first {
        it.getReference<MethodReference>()?.name == "getString"
    }.registersUsed.first()
    val storageEnd = code.indexOfFirst {
        val ref = it.getReference<MethodReference>()
        ref?.definingClass == "Ljava/lang/StringBuilder;" && ref.name == "toString"
    } + 2
    val registers = render.implementation!!.registerCount

    // Keep YTM's storage units, localized formatting and storage-provider calls verbatim.
    controller.addNativeRead("patch_getStorageSummary", registers, controllerRegister, contextRegister,
        code.subList(controllerLoad + 1, storageEnd))
    val permission = code.mapNotNull { it.getReference<MethodReference>() }.single {
        it.parameterTypes == listOf(CONTEXT) && it.returnType == "Z"
    }
    controller.addPatchInterface(NATIVE, "patch_showDeviceFilesAvailable", """
        invoke-static { p1 }, $permission
        move-result v0
        return v0
    """)

    val toggleIndexes = code.indices.filter { index ->
        val ref = code[index].getReference<MethodReference>()
        ref?.definingClass == TOGGLE && ref.name == "<init>"
    }
    val toggles = listOf(
        "ShowDeviceFiles" to "pref_show_device_files",
        "WifiOnly" to "pref_offline_mixtape_only_wifi",
        "SmartDownloads" to "auto_offline_title"
    )
    check(toggleIndexes.size == toggles.size) { "Unexpected number of native car settings toggles" }
    // Bind listeners by the actual row labels. A reordered native screen must never change a different setting.
    toggles.forEachIndexed { index, (_, label) ->
        val titleId = resourceId(ResourceType.STRING, label)
        val next = toggleIndexes.getOrNull(index + 1) ?: code.size
        check(code.subList(toggleIndexes[index], next).any { (it as? WideLiteralInstruction)?.wideLiteral == titleId }) {
            "Could not locate the native car settings row for $label"
        }
    }
    val callbacks = toggleIndexes.map { end ->
        code.subList(0, end).indexOfLast {
            val ref = it.getReference<MethodReference>()
            ref?.name == "<init>" && ref.parameterTypes.firstOrNull() == controller.type
        }
    }
    // YTM 9.15 shows Smart downloads unconditionally. Later versions use the current account's entitlement.
    val gateStart = toggleIndexes[1] + 1
    val gates = (gateStart until callbacks[2]).filter { code[it].opcode == Opcode.IF_EQZ }
    check(gates.size <= 1) { "Unexpected Smart downloads availability check" }
    val gate = gates.singleOrNull()
    if (gate == null) {
        controller.addPatchInterface(NATIVE, "patch_smartDownloadsAvailable", "const/4 v0, 1\nreturn v0")
    } else {
        val start = controller.readStart(code, gate)
        controller.addNativeRead("patch_smartDownloadsAvailable", registers, controllerRegister, null,
            code.subList(start, gate))
    }

    toggles.forEachIndexed { index, (name, _) ->
        val end = code.subList(callbacks[index], toggleIndexes[index]).indexOfLast {
            it.opcode == Opcode.IPUT_BOOLEAN
        } + callbacks[index]
        check(end >= callbacks[index])
        val start = controller.readStart(code, end)
        controller.addNativeRead("patch_${name.replaceFirstChar { it.lowercase() }}", registers,
            controllerRegister, null, code.subList(start, end))
        bindNativeListener(controller, "patch_set$name", code, callbacks[index])
    }
}

private fun MutableClass.readStart(code: List<Instruction>, end: Int) = (0 until end).last {
    code[it].opcode == Opcode.IGET_OBJECT && code[it].getReference<FieldReference>()?.definingClass == type
}

/** Install only the linear stock computation. The native screen's CarContext is used solely for resources. */
private fun MutableClass.addNativeRead(name: String, locals: Int, controllerReg: Int,
                                       contextReg: Int?, stock: List<Instruction>) {
    val declaration = classDefBy(NATIVE).methods.single { it.name == name }
    check(stock.none { it is OffsetInstruction } &&
            stock.last().opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT))
    val body = buildList<Instruction> {
        add(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, controllerReg, locals))
        if (contextReg != null) add(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, contextReg, locals + 1))
        stock.forEach { instruction ->
            val ref = instruction.getReference<MethodReference>()
            if (contextReg != null && ref?.name == "getResources" && ref.parameterTypes.isEmpty()) {
                add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, contextReg, 0, 0, 0, 0,
                    ImmutableMethodReference(CONTEXT, ref.name, ref.parameterTypes, ref.returnType)))
            } else add(instruction)
        }
        val returnOpcode = if (declaration.returnType == "Z") Opcode.RETURN else Opcode.RETURN_OBJECT
        add(ImmutableInstruction11x(returnOpcode, stock.last().registersUsed.single()))
    }
    val implementation = ImmutableMethodImplementation(locals + 1 + declaration.parameters.size, body, emptyList(), null)
    methods.add(ImmutableMethod(type, name, declaration.parameters, declaration.returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, emptySet(), emptySet(), implementation).toMutable())
}

/** Dispatch the same native listener as the template so account writes and refresh side effects are preserved. */
private fun bindNativeListener(controller: MutableClass, name: String, code: List<Instruction>, index: Int) {
    val constructor = code[index].getReference<MethodReference>()!!
    val callback = classDefBy(constructor.definingClass).virtualMethods.single {
        it.parameterTypes == listOf("Z") && it.returnType == "V"
    }
    val initialize = when (constructor.parameterTypes.size) {
        1 -> "invoke-direct { v0, p0 }, $constructor"
        2 -> {
            // R8 combines the three listeners into one class with a discriminator on newer builds.
            val register = code[index].registersUsed.last()
            val literal = code.take(index).last { it.opcode.setsRegister() && it.registersUsed.firstOrNull() == register }
            val value = (literal as WideLiteralInstruction).wideLiteral
            "const v1, $value\ninvoke-direct { v0, p0, v1 }, $constructor"
        }
        else -> error("Unexpected native car settings listener constructor: $constructor")
    }
    controller.addPatchInterface(NATIVE, name, """
        new-instance v0, ${constructor.definingClass}
        $initialize
        invoke-virtual { v0, p1 }, $callback
        return-void
    """)
}
