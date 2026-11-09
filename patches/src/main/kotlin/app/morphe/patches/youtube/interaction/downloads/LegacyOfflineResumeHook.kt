/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.video.information.ChannelInformationFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal fun enableLegacyOfflineResume(
    playerResponseModelType: String,
    descriptorType: String,
    shouldResume: String,
) {
    if (is_20_51_or_greater) {
        enableOfflineResumeWithLegacyResult(playerResponseModelType, descriptorType, shouldResume)
    } else {
        enableOfflineResumeBefore2051(playerResponseModelType, descriptorType, shouldResume)
    }
}

private fun readLegacyResumePositionFilters(configType: String) = listOf(
    methodCall(definingClass = configType, parameters = emptyList(), returnType = "Z"),
    opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
    opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
    methodCall(
        opcode = Opcode.INVOKE_INTERFACE,
        parameters = emptyList(),
        returnType = "L",
        location = MatchAfterImmediately()
    ),
    opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    fieldAccess(opcode = Opcode.IGET_WIDE, type = "J", location = MatchAfterImmediately())
)

private fun enableOfflineResumeWithLegacyResult(
    playerResponseModelType: String,
    descriptorType: String,
    shouldResume: String,
) {
    val resumeGate = Fingerprint(
        parameters = emptyList(),
        returnType = "Z",
        filters = listOf(literal(45732853))
    ).method
    val startup = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf("L", playerResponseModelType, descriptorType),
        returnType = "V",
        filters = listOf(
            methodCall(resumeGate),
            opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
        ) + readLegacyResumePositionFilters(resumeGate.definingClass)
    )
    val refresh = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf(startup.method.parameterTypes.first().toString(), descriptorType),
        returnType = "V",
        filters = listOf(
            methodCall(
                opcode = Opcode.INVOKE_INTERFACE,
                parameters = emptyList(),
                returnType = playerResponseModelType
            ),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            methodCall(resumeGate),
            opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
            literal(0, opcodes = listOf(Opcode.CONST_WIDE_16), location = MatchAfterImmediately())
        )
    )

    // Before 21.23.492, the resolver writes the start position to the playback state.
    // Startup checks a separate flag before reading that position.
    val startupMatches = startup.instructionMatches
    for ((gate, descriptorRegister, responseRegister) in listOf(
        Triple(startupMatches[3], 8, 9),
        Triple(startupMatches[1], 4, 5)
    )) {
        val result = gate.getInstruction<OneRegisterInstruction>().registerA
        startup.method.addInstructionsWithLabels(
            gate.index + 1,
            """
                if-nez v$result, :resume
                move-object/from16 v$descriptorRegister, p3
                move-object/from16 v$responseRegister, p2
                invoke-static { v$descriptorRegister, v$responseRegister }, $shouldResume
                move-result v$result
                :resume
                nop
            """
        )
    }

    val refreshMatches = refresh.instructionMatches
    val responseGetter = refreshMatches[0]
    val playbackContext = responseGetter.getInstruction<FiveRegisterInstruction>().registerC
    val result = refreshMatches[3].getInstruction<OneRegisterInstruction>().registerA
    val scratch = refreshMatches[4].getInstruction<OneRegisterInstruction>().registerA
    // The refresh method reuses the response register before deciding how to resume.
    refresh.method.addInstructionsWithLabels(
        refreshMatches[4].index,
        """
            if-nez v$result, :resume
            if-eqz p2, :resume
            invoke-interface { v$playbackContext }, ${responseGetter.methodReference}
            move-result-object v${scratch + 1}
            move-object/from16 v$scratch, p2
            invoke-static { v$scratch, v${scratch + 1} }, $shouldResume
            move-result v$result
            :resume
            nop
        """
    )
}

private fun enableOfflineResumeBefore2051(
    playerResponseModelType: String,
    descriptorType: String,
    shouldResume: String,
) {
    val startup = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf("L", playerResponseModelType, descriptorType),
        returnType = "V",
        filters = listOf(
            fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
            fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/Object;", location = MatchAfterImmediately()),
            checkCast("L", location = MatchAfterImmediately()),
            literal(45650826, location = MatchAfterImmediately()),
            fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
            methodCall(
                opcode = Opcode.INVOKE_INTERFACE,
                parameters = emptyList(),
                returnType = "L",
                location = MatchAfterImmediately()
            ),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                parameters = listOf("L"),
                returnType = "V",
                location = MatchAfterImmediately()
            ),
            literal(0, opcodes = listOf(Opcode.CONST_WIDE_16), location = MatchAfterImmediately())
        )
    )
    val startupMatches = startup.instructionMatches
    val startupResult = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = startup.method.parameterTypes.map { it.toString() },
        returnType = "V",
        filters = readLegacyResumePositionFilters(startupMatches[0].fieldReference.type)
    ).instructionMatches[1]

    val refresh = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf(startup.method.parameterTypes.first().toString(), descriptorType),
        returnType = "V",
        filters = listOf(
            methodCall(
                opcode = Opcode.INVOKE_INTERFACE,
                parameters = emptyList(),
                returnType = playerResponseModelType
            ),
            methodCall(
                opcode = Opcode.INVOKE_INTERFACE,
                definingClass = playerResponseModelType,
                parameters = emptyList(),
                returnType = "Lcom/google/android/libraries/youtube/innertube/model/media/PlayerConfigModel;"
            ),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            methodCall(
                definingClass = "Lcom/google/android/libraries/youtube/innertube/model/media/PlayerConfigModel;",
                parameters = emptyList(),
                returnType = "J",
                location = MatchAfterImmediately()
            ),
            opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately()),
            literal(0, opcodes = listOf(Opcode.CONST_WIDE_16), location = MatchAfterImmediately()),
            opcode(Opcode.CMP_LONG, location = MatchAfterImmediately()),
            opcode(Opcode.IF_LEZ, location = MatchAfterImmediately()),
            fieldAccess(reference = startupMatches[4].fieldReference, opcode = Opcode.IGET_OBJECT),
            methodCall(startupMatches[8].methodReference)
        )
    )
    val refreshMatches = refresh.instructionMatches
    val responseGetter = refreshMatches[0]
    val playbackContext = responseGetter.getInstruction<FiveRegisterInstruction>().registerC
    val scratch = refreshMatches[6].getInstruction<OneRegisterInstruction>().registerA
    refresh.method.addInstructionsWithLabels(
        refreshMatches[6].index,
        """
            if-eqz p2, :resume
            invoke-interface { v$playbackContext }, ${responseGetter.methodReference}
            move-result-object v6
            move-object/from16 v$scratch, p2
            invoke-static { v$scratch, v6 }, $shouldResume
            move-result v$scratch
            if-nez v$scratch, :resolve
            :resume
            nop
        """,
        ExternalLabel("resolve", refresh.method.getInstruction(refreshMatches[8].index))
    )

    // Before 20.51.39, startup branches to the resolver before it can reuse the descriptor register.
    // Keep the scope check in v5 until startup selects the position stored by the resolver.
    val result = startupResult.getInstruction<OneRegisterInstruction>().registerA
    startup.method.addInstructions(startupResult.index + 1, "or-int/2addr v$result, v5")
    startup.method.addInstructionsWithLabels(
        startupMatches[0].index + 1,
        """
            invoke-static { p3, p2 }, $shouldResume
            move-result v5
            if-nez v5, :resolve
        """,
        ExternalLabel("resolve", startup.method.getInstruction(startupMatches[4].index))
    )
}
