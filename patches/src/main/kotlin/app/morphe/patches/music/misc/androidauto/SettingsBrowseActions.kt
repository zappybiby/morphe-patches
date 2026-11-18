package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.string
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.util.toPublicAccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SERVICE = "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"
private const val EXTENSION = "Lapp/morphe/extension/music/patches/AndroidAutoPatch;"
private const val ITEM = "Landroid/support/v4/media/MediaBrowserCompat\$MediaItem;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

internal fun patchSettingsBrowseActions() {
    advertiseSettingsActions()
    // The compat library reads the root hints on CONNECT / REGISTER_CALLBACK_MESSENGER
    // but R8 discards them before creating the connection used for subsequent requests.
    Fingerprint(
        name = "handleMessage",
        parameters = listOf("Landroid/os/Message;"),
        strings = listOf("data_root_hints", "data_calling_uid", "data_callback_token")
    ).method.addInstruction(0, "invoke-static/range { p1 .. p1 }, ${EXTENSION}->observeSettingsBrowserMessage(Landroid/os/Message;)V")

    val base = classDefBy(classDefBy(SERVICE).superclass!!).superclass!!
    val reload = mediaBrowserReloadFingerprint(base).originalMethod
    val connectionType = reload.parameterTypes[1].toString()
    // performLoadChildren sets the current connection before entering an app callback and clears it afterwards.
    val current = reload.instructions.first { it.opcode == Opcode.IPUT_OBJECT &&
            it.getReference<FieldReference>()?.type == connectionType }.getReference<FieldReference>()!!
    // Older compat versions keep this field package-private, outside the MusicBrowserService package.
    val currentField = mutableClassDefBy(current.definingClass).instanceFields.single { it == current }
    currentField.accessFlags = currentField.accessFlags.toPublicAccessFlags()
    hookSettingsCallbacks(current)
}

private fun advertiseSettingsActions() {
    val root = Fingerprint(
        definingClass = SERVICE,
        strings = listOf("MBS: getRoot() Succeeded for: %s")
    ).method
    // Earlier branches return empty roots for denied or incomplete connections.
    val constructor = root.instructions.indexOfLast {
        val call = it.getReference<MethodReference>()
        call?.definingClass == root.returnType && call.name == "<init>" &&
                call.parameterTypes == listOf(STRING, BUNDLE)
    }
    check(constructor >= 0) { "Could not find the successful media browser root" }
    val extras = root.instructions[constructor].registersUsed.last()
    val hints = root.parameterTypes.indexOf(BUNDLE) + 1
    check(hints >= 2 && root.parameterTypes.first() == STRING)
    // Preserve the incoming package and hints, even if the original method reuses parameter registers.
    val (method, shift) = root.addFreeRegisters(root.parameterTypes.size + 1)
    method.addInstructions(constructor + shift + 1, """
        move-object/from16 p0, p1
        move-object/from16 p1, p$hints
        move-object/from16 p2, v$extras
        invoke-static/range { p0 .. p2 }, ${EXTENSION}->addSettingsBrowseActions($STRING$BUNDLE$BUNDLE)V
    """)
}

private fun hookSettingsCallbacks(current: FieldReference) {
    val action = Fingerprint(
        definingClass = SERVICE,
        parameters = listOf(BUNDLE, "L"),
        filters = listOf(string("sendError() called when either sendResult() or sendError() had already been called for: "))
    ).method
    val resultType = action.parameterTypes.last().toString()
    // R8 eliminates onCustomAction's action parameter. Result retains the exact action supplied by the client.
    val actionKey = classDefBy(resultType).instanceFields.single { it.type == OBJECT }
    val item = Fingerprint(
        definingClass = SERVICE,
        parameters = listOf(STRING, resultType),
        filters = listOf(
            fieldAccess(opcode = Opcode.IPUT, definingClass = resultType, type = "I"),
            methodCall(definingClass = resultType, parameters = listOf(OBJECT), returnType = "V")
        )
    )
    val sendResult = item.instructionMatches.last().methodReference
    action.addFreeRegisters(3).first.addInstructionsWithLabels(0, """
        move-object/from16 v0, p0
        iget-object v0, v0, $current
        move-object/from16 v2, p2
        iget-object v1, v2, $actionKey
        move-object/from16 v2, p1
        invoke-static { v0, v1, v2 }, ${EXTENSION}->handleSettingsBrowseAction($OBJECT$OBJECT$BUNDLE)$BUNDLE
        move-result-object v0
        if-eqz v0, :stock
        move-object/from16 v1, p2
        invoke-virtual { v1, v0 }, $sendResult
        return-void
        :stock
        nop
    """)
    item.method.addFreeRegisters(3).first.addInstructionsWithLabels(0, """
        move-object/from16 v0, p0
        iget-object v0, v0, $current
        move-object/from16 v1, p1
        invoke-static { v0, v1 }, ${EXTENSION}->getSettingsBrowseItem($OBJECT$STRING)$ITEM
        move-result-object v0
        if-eqz v0, :stock
        move-object/from16 v1, p2
        invoke-virtual { v1, v0 }, $sendResult
        return-void
        :stock
        nop
    """)
}
