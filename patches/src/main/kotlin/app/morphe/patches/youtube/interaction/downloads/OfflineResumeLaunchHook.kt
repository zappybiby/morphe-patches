/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.video.information.ChannelInformationFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

internal fun enableOfflineResumeOnLaunch(
    commandType: String,
    modelType: String,
    responseGetter: String,
    responseType: String,
    offlineState: FieldReference,
    resolverType: String?,
) {
    val descriptor = PlaybackStartDescriptorToStringFingerprint.classDef
    val descriptorType = descriptor.type
    val command = Fingerprint(
        definingClass = descriptorType,
        name = "<init>",
        filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = commandType))
    ).matchAll(1..1).single().instructionMatches.single().fieldReference
    val scopeName = "patch_preferFreshOfflinePosition"
    val shouldResume = "$descriptorType->$scopeName($descriptorType${modelType})Z"
    descriptor.addBridge(
        scopeName, listOf(descriptorType, modelType), "Z", 4,
        """
            if-eqz p0, :skip
            if-eqz p1, :skip
            iget-object v0, p0, $command
            if-eqz v0, :skip
            invoke-interface { p1 }, $modelType->$responseGetter()$responseType
            move-result-object v1
            if-eqz v1, :skip
            iget-object v1, v1, $offlineState
            invoke-static { v0, v1 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->preferFreshOfflinePosition(Lcom/google/protobuf/MessageLite;Lcom/google/protobuf/MessageLite;)Z
            move-result v0
            return v0
            :skip
            const/4 v0, 0x0
            return v0
        """,
        publicStatic
    )

    if (is_21_23_or_greater) {
        // The startup method receives the response and descriptor in consecutive registers.
        descriptor.addBridge(
            scopeName, listOf(modelType, descriptorType), "Z", 3,
            """
                invoke-static { p1, p0 }, $shouldResume
                move-result v0
                return v0
            """,
            publicStatic
        )
        enableOfflineResumeInDirector(
            modelType, descriptorType, shouldResume, "$descriptorType->$scopeName($modelType${descriptorType})Z"
        )
        preferFreshOfflinePositionInResolver(checkNotNull(resolverType), modelType, descriptorType, shouldResume)
    } else {
        enableLegacyOfflineResume(modelType, descriptorType, shouldResume)
    }
}

private fun enableOfflineResumeInDirector(
    modelType: String,
    descriptorType: String,
    shouldResume: String,
    shouldResumeAtStartup: String,
) {
    val gate = Fingerprint(
        parameters = emptyList(),
        returnType = "Z",
        filters = listOf(literal(45732853))
    ).method
    val startup = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf("L", modelType, descriptorType),
        returnType = "V",
        filters = listOf(
            methodCall(gate),
            opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
        )
    )
    val refresh = Fingerprint(
        classFingerprint = ChannelInformationFingerprint,
        parameters = listOf(startup.method.parameterTypes.first().toString(), descriptorType),
        returnType = "V",
        filters = listOf(
            methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = emptyList(), returnType = modelType),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            methodCall(gate),
            opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
        )
    )

    // On 21.23.492+, the director can bypass the offline resolver at startup and when applying
    // a refreshed player response. Let a playable download opened from its card use either path.
    val startupGate = startup.instructionMatches.last()
    val startupResult = startupGate.getInstruction<OneRegisterInstruction>().registerA
    startup.method.addInstructionsWithLabels(
        startupGate.index + 1,
        """
            if-nez v$startupResult, :resume
            invoke-static/range { p2 .. p3 }, $shouldResumeAtStartup
            move-result v$startupResult
            :resume
            nop
        """
    )

    val (_, response, _, refreshGate) = refresh.instructionMatches
    val modelRegister = response.getInstruction<OneRegisterInstruction>().registerA
    val refreshResult = refreshGate.getInstruction<OneRegisterInstruction>().registerA
    refresh.method.addInstructionsWithLabels(
        refreshGate.index + 1,
        """
            if-nez v$refreshResult, :resume
            move-object/from16 v$refreshResult, p2
            invoke-static { v$refreshResult, v$modelRegister }, $shouldResume
            move-result v$refreshResult
            :resume
            nop
        """
    )
}

private fun preferFreshOfflinePositionInResolver(
    resolverType: String,
    modelType: String,
    descriptorType: String,
    shouldResume: String,
) {
    val resolver = mutableClassDefBy(resolverType)
    val resolve = Fingerprint(
        definingClass = resolver.type,
        parameters = listOf(descriptorType, modelType, "L"),
        returnType = "Ljava/lang/Long;",
        filters = listOf(
            methodCall(definingClass = descriptorType, parameters = emptyList(), returnType = "Z"),
            opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
            opcode(Opcode.IF_EQ, location = MatchAfterImmediately()),
            methodCall(
                definingClass = descriptorType,
                parameters = emptyList(),
                returnType = "J",
                location = MatchAfterWithin(10)
            ),
            opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately()),
            methodCall("Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;", location = MatchAfterImmediately())
        ) + List(4) {
            methodCall(
                opcode = Opcode.INVOKE_STATIC,
                definingClass = "this",
                parameters = listOf(modelType),
                returnType = "Ljava/lang/Long;"
            )
        }
    ).matchAll(1..1).single()
    val (hasStart, hasStartResult, _, getStart) = resolve.instructionMatches
    val fallbackCalls = resolve.instructionMatches.takeLast(4)
    val fallback = fallbackCalls.first().methodReference
    check(fallbackCalls.all { it.methodReference == fallback })
    val result = hasStartResult.getInstruction<OneRegisterInstruction>().registerA
    val fallbackName = "patch_resolveOfflineStartFallback"
    val fallbackRef = "${resolver.type}->$fallbackName($descriptorType${modelType})Ljava/lang/Long;"

    resolver.addBridge(
        fallbackName, listOf(descriptorType, modelType), "Ljava/lang/Long;", 4,
        """
            invoke-static { p0, p1 }, $shouldResume
            move-result v0
            if-eqz v0, :use_response
            invoke-virtual { p0 }, ${hasStart.methodReference}
            move-result v0
            if-eqz v0, :use_response
            invoke-virtual { p0 }, ${getStart.methodReference}
            move-result-wide v0
            invoke-static { v0, v1 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v0
            return-object v0
            :use_response
            invoke-static { p1 }, $fallback
            move-result-object v0
            return-object v0
        """,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
    )

    // On 21.23.492+, the resolver returns the command's explicit start before reading saved progress.
    // For a marked card, use that time only when the resolver falls back to the response's start.
    fallbackCalls.forEach {
        resolve.method.replaceInstruction(it.index, "invoke-static/range { p1 .. p2 }, $fallbackRef")
    }
    resolve.method.addInstructionsWithLabels(
        hasStartResult.index + 1,
        """
            if-eqz v$result, :resume
            invoke-static/range { p1 .. p2 }, $shouldResume
            move-result v$result
            xor-int/lit8 v$result, v$result, 0x1
            :resume
            nop
        """
    )
}
