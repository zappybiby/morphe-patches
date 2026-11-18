package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val MEDIA_SERVICE = "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"
private const val SETTINGS_SERVICE =
    "Lcom/google/android/apps/youtube/music/mediabrowser/androidautoprojected/AndroidAutoCarAppServices;"
private const val TAB = "Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab"
private const val ROWS = "${TAB}\$Rows;"
private const val SWITCH = "${TAB}\$Switch;"
private const val CONTEXT = "Landroid/content/Context;"
private const val MODEL = "Landroidx/car/app/model/"

internal fun patchNativeCarSettings() {
    val injection = classDefBy(classDefBy(SETTINGS_SERVICE).superclass!!).methods.single { it.name == "onCreate" }
    val code = injection.instructions.toList()
    val field = code.mapNotNull { it.getReference<FieldReference>() }.single { it.definingClass == SETTINGS_SERVICE }
    val screen = Fingerprint(filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = SETTINGS_SERVICE, name = field.name),
        methodCall(name = "<init>", parameters = listOf("L", field.type))
    )).instructionMatches.last().methodReference
    val render = Fingerprint(definingClass = screen.definingClass, parameters = emptyList(), filters = listOf(
        methodCall(definingClass = "${MODEL}Toggle;", name = "<init>"),
        methodCall(definingClass = "${MODEL}ListTemplate;", name = "<init>")
    )).originalMethod
    val rows = copyStockRows(render, field.type, screen.parameterTypes.first().toString())

    // The media service resolves the current controller through the same provider as YTM's stock Settings screen.
    val assign = code.indexOfLast { it.getReference<FieldReference>() == field }
    val provider = code.take(assign).last { it.opcode == Opcode.INVOKE_INTERFACE }.getReference<MethodReference>()!!
    val graph = code.take(assign).takeLastWhile { it.getReference<TypeReference>()?.type != SETTINGS_SERVICE }
        .filter { it.opcode == Opcode.IGET_OBJECT }.map { it.getReference<FieldReference>()!! }
    val media = mutableClassDefBy(classDefBy(MEDIA_SERVICE).superclass!!)
    val component = media.methods.single { it.name == "generatedComponent" && it.parameterTypes.isEmpty() }
    check(media.methods.single { it.name == "onCreate" }.instructions.any {
        it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == graph.first().definingClass
    })
    media.addPatchInterface("${TAB}\$Source;", "patch_loadSettings", """
        invoke-virtual { p0 }, $component
        move-result-object v0
        check-cast v0, ${graph.first().definingClass}
        ${graph.joinToString("\n") { "iget-object v0, v0, $it" }}
        invoke-interface { v0 }, $provider
        move-result-object v0
        check-cast v0, ${field.type}
        invoke-static { v0, p0, p1 }, $rows
        return-void
    """)
}

/** Run YTM's stock row builder with the media service's Context and forward each row with its original switch listener. */
private fun copyStockRows(render: Method, controller: String, carContext: String): Method {
    val code = render.instructions.toList()
    val builder = code.first().getReference<TypeReference>()!!.type
    val adds = code.indices.filter { i ->
        code[i].getReference<MethodReference>()?.let {
            it.definingClass == builder && it.parameterTypes.size == 1 && it.returnType == "V"
        } == true
    }
    check(adds.size == 4) { "Unexpected stock car settings rows" }
    val end = adds.last() + 1
    val args = render.implementation!!.registerCount
    val listener = args + 5
    val body = ImmutableMethodImplementation(args + 6 + 3,
        code.take(end) + ImmutableInstruction10x(Opcode.RETURN_VOID), emptyList(), null)
    val copy = ImmutableMethod(render.definingClass, "patch_render", listOf(controller, CONTEXT, ROWS).map {
        ImmutableMethodParameter(it, null, null)
    }, "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, body).toMutable()

    for (i in end - 1 downTo 0) {
        val insn = code[i]
        val field = insn.getReference<FieldReference>()
        val call = insn.getReference<MethodReference>()
        val regs = insn.registersUsed
        when {
            i in adds -> {
                // Use the same resource ID as this row's title, even when earlier rows are hidden.
                val start = (adds.lastOrNull { it < i } ?: -1) + 1
                val title = (start until i).first { index ->
                    code[index].getReference<MethodReference>()?.let { it.definingClass == carContext && it.name == "getString" } == true
                }
                val id = (code[title - 1] as WideLiteralInstruction).wideLiteral
                val row = regs.last()
                val hasToggle = (start until i).any { index ->
                    code[index].getReference<MethodReference>()?.definingClass == "${MODEL}Toggle;"
                }
                val readChecked = if (hasToggle) """
                    invoke-virtual { v$row }, ${MODEL}Row;->getToggle()${MODEL}Toggle;
                    move-result-object v$row
                    invoke-virtual { v$row }, ${MODEL}Toggle;->isChecked()Z
                    move-result v${args + 4}
                """ else "const/16 v${args + 4}, 0"
                copy.addInstructions(i + 1, """
                    const v${args + 1}, $id
                    invoke-virtual { v$row }, ${MODEL}Row;->getTitle()${MODEL}CarText;
                    move-result-object v${args + 2}
                    invoke-virtual/range { v${args + 2} .. v${args + 2} }, Ljava/lang/Object;->toString()Ljava/lang/String;
                    move-result-object v${args + 2}
                    invoke-virtual { v$row }, ${MODEL}Row;->getTexts()Ljava/util/List;
                    move-result-object v${args + 3}
                    $readChecked
                    invoke-interface/range { v$args .. v$listener }, ${ROWS}->add(ILjava/lang/String;Ljava/util/List;Z$SWITCH)V
                    const/16 v$listener, 0
                """)
            }
            field?.type == controller || field?.type == carContext -> copy.replaceInstruction(i,
                "move-object/from16 v${regs.first()}, ${if (field.type == controller) "p0" else "p1"}")
            call?.definingClass == carContext -> {
                check(call.name == "getString" || call.name == "getResources")
                copy.replaceInstruction(i, "invoke-virtual { ${regs.joinToString { "v$it" }} }, " +
                        call.toString().replaceBefore("->", CONTEXT))
            }
            call?.name == "<init>" && call.parameterTypes.firstOrNull() == controller -> {
                val callback = mutableClassDefBy(call.definingClass)
                if (SWITCH !in callback.interfaces) {
                    val change = callback.methods.single { it.parameterTypes == listOf("Z") && it.returnType == "V" }
                    callback.addPatchInterface(SWITCH, "patch_set", "invoke-virtual { p0, p1 }, $change\nreturn-void")
                }
                copy.addInstructions(i + 1, "move-object/16 v$listener, v${regs.first()}")
            }
        }
    }
    copy.addInstructions(0, "move-object/from16 v$args, p2\nconst/16 v$listener, 0")
    mutableClassDefBy(render.definingClass).methods.add(copy)
    return copy
}
