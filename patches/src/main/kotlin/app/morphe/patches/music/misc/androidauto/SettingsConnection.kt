package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val SETTINGS_CONNECTION =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$SettingsBrowserConnection;"

/** Exposes the same connection identity check that MediaBrowserServiceCompat uses to deliver folder results. */
internal fun patchSettingsConnection() {
    val next = MatchAfterImmediately()
    val guard = Fingerprint(
        returnType = "V",
        parameters = listOf("Ljava/lang/Object;"),
        strings = listOf("Calling onLoadChildren() failed for id="),
        filters = listOf(
            fieldAccess(opcode = Opcode.IGET_OBJECT), // Folder result's connection
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = next), // Connection's callbacks
            methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = emptyList(),
                returnType = "Landroid/os/IBinder;", location = next),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = next),
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = next), // Folder result's service
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = next), // Service's connection map
            methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "get", parameters = listOf("Ljava/lang/Object;"),
                returnType = "Ljava/lang/Object;", location = next),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = next),
            opcode(Opcode.IF_NE, location = next),
        ),
    ).instructionMatches
    val connection = mutableClassDefBy(guard[0].fieldReference.type)
    val callbacks = guard[1].fieldReference
    val asBinder = guard[2].methodReference
    val serviceType = guard[4].fieldReference.type
    val registry = guard[5].fieldReference
    val get = guard[6].methodReference
    val service = connection.instanceFields.single { it.type == serviceType }
    val packageName = connection.instanceFields.single { it.type == "Ljava/lang/String;" }
    // ConnectionRecord(service, packageName, pid, uid, callbacks) stores both pid and uid.
    val constructor = connection.methods.single {
        it.name == "<init>" && it.parameterTypes == listOf(serviceType, packageName.type, "I", "I", callbacks.type)
    }
    val uid = constructor.instructions.single {
        it.opcode == Opcode.IPUT && it.registersUsed.first() == constructor.p0Register + 4
    }.getReference<FieldReference>()!!

    connection.addPatchInterface(SETTINGS_CONNECTION, "patch_getSettingsBrowserPackage", packageName)
    connection.addPatchInterface(SETTINGS_CONNECTION, "patch_getSettingsBrowserUid", uid)
    val binderGetter = connection.addPatchInterface(SETTINGS_CONNECTION, "patch_getSettingsBrowserBinder", """
        iget-object v0, p0, $callbacks
        if-eqz v0, :disconnected
        invoke-virtual { v0 }, $asBinder
        move-result-object v0
        :disconnected
        return-object v0
    """)
    connection.addPatchInterface(SETTINGS_CONNECTION, "patch_isSettingsBrowserConnected", """
        invoke-virtual { p0 }, $binderGetter
        move-result-object v0
        if-eqz v0, :disconnected
        iget-object v1, p0, $service
        iget-object v1, v1, $registry
        invoke-virtual { v1, v0 }, $get
        move-result-object v0
        if-ne v0, p0, :disconnected
        const/4 v0, 0x1
        return v0
        :disconnected
        const/4 v0, 0x0
        return v0
    """)
}
