package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private object DownloadReadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "J", "J", "L", "Ljava/lang/String;", "L", "L"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", type = "Z"),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf("[B", "I", "I"), returnType = "I", location = MatchAfterWithin(4)),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_LTZ, location = MatchAfterImmediately()),
        opcode(Opcode.ADD_LONG, location = MatchAfterWithin(4)),
        fieldAccess(opcode = Opcode.IGET_WIDE, type = "J", location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf("L", "J"), returnType = "V"),
        // Music compares this request's byte count with its length when a transfer is preempted.
        fieldAccess(opcode = Opcode.IGET_WIDE, type = "J"),
        opcode(Opcode.CMP_LONG, location = MatchAfterImmediately()),
    ),
)

internal fun checkDownloadLength(extensionClass: String) = with(DownloadReadFingerprint) {
    val cancelled = instructionMatches[0]
    val worker = cancelled.getInstruction<TwoRegisterInstruction>().registerB
    val eof = instructionMatches[4]
    val result = eof.getInstruction<OneRegisterInstruction>().registerA
    val count = instructionMatches[5].getInstruction<OneRegisterInstruction>().registerA
    val range = instructionMatches[6].getInstruction<TwoRegisterInstruction>().registerB
    val length = instructionMatches[8].fieldReference

    val validator = ImmutableMethod(
        classDef.type, "patch_checkDownloadLength",
        listOf(ImmutableMethodParameter("J", null, null), ImmutableMethodParameter(length.definingClass, null, null)),
        "V", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(5),
    ).toMutable().apply {
        addInstructions(0, """
            iget-wide v0, p2, $length
            invoke-static {p0, p1, v0, v1}, $extensionClass->checkDownloadLength(JJ)V
            return-void
        """)
    }
    classDef.methods.add(validator)

    with(method) {
        val cleanup = getInstruction<BuilderOffsetInstruction>(eof.index).target
        val nextRead = implementation!!.newLabelForIndex(eof.index + 1)
        // Only the EOF path needs the check. The scratch slot after the count is dead on this path.
        addInstructionsWithLabels(eof.index + 1, """
            iget-boolean v$result, v$worker, ${cancelled.fieldReference}
            if-nez v$result, :finish
            move-object/16 v${count + 2}, v$range
            invoke-static/range {v$count .. v${count + 2}}, $validator
            :finish
            const/16 v$result, -0x1
            goto :cleanup
        """, ExternalLabel("cleanup", cleanup.location.instruction))
        replaceInstruction(eof.index, BuilderInstruction21t(Opcode.IF_GEZ, result, nextRead))
    }
}
