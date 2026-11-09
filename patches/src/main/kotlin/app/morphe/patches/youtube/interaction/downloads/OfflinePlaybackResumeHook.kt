/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal fun enableOfflinePlaybackResume(
    commandType: String,
    playerResponseModelType: String,
    playerResponseGetter: String,
    playerResponseType: String,
    offlineStateField: FieldReference,
    resolverType: String?,
) {
    val descriptorClass = PlaybackStartDescriptorToStringFingerprint.classDef
    val descriptorType = descriptorClass.type
    val commandField = playbackStartDescriptorCommandFingerprint(
        descriptorType,
        commandType
    ).instructionMatches.single().fieldReference
    val shouldResumeMethodName = "patch_shouldResumeOfflinePlayback"
    val shouldResumeMethod = "$descriptorType->$shouldResumeMethodName($descriptorType$playerResponseModelType)Z"
    descriptorClass.addBridge(
        shouldResumeMethodName,
        listOf(descriptorType, playerResponseModelType),
        "Z",
        4,
        """
            if-eqz p0, :skip
            if-eqz p1, :skip
            iget-object v0, p0, $commandField
            if-eqz v0, :skip
            invoke-interface { p1 }, $playerResponseModelType->$playerResponseGetter()$playerResponseType
            move-result-object v1
            if-eqz v1, :skip
            iget-object v1, v1, $offlineStateField
            invoke-static { v0, v1 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->shouldResumeOfflinePlayback(Lcom/google/protobuf/MessageLite;Lcom/google/protobuf/MessageLite;)Z
            move-result v0
            return v0
            :skip
            const/4 v0, 0x0
            return v0
        """,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
    )

    enableOfflineResumeInDirector(playerResponseModelType, descriptorType, shouldResumeMethod, resolverType)
    if (is_21_23_or_greater) {
        preferSavedOfflinePlaybackPosition(
            checkNotNull(resolverType),
            playerResponseModelType,
            descriptorType,
            shouldResumeMethod
        )
    }
}

private fun enableOfflineResumeInDirector(
    playerResponseModelType: String,
    descriptorType: String,
    shouldResumeMethod: String,
    resolverType: String?,
) {
    val startupMethod = offlinePlaybackStartFingerprint(playerResponseModelType, descriptorType).method
    val directorClass = mutableClassDefBy(startupMethod.definingClass)
    val contextType = startupMethod.parameterTypes.first().toString()
    val lookupMatches = offlinePositionLookupFingerprint(
        startupMethod,
        contextType,
        playerResponseModelType,
        descriptorType,
        resolverType
    ).instructionMatches
    val nativeResolverMethod = lookupMatches.last().methodReference
    val refreshFingerprint = offlinePlayerResponseRefreshFingerprint(
        directorClass.type,
        contextType,
        descriptorType,
        playerResponseModelType
    )
    val contextPlayerField = refreshFingerprint.instructionMatches.first().fieldReference
    val playerResponseGetter = refreshFingerprint.instructionMatches.last().methodReference
    val callbackInstructions = if (is_20_51_or_greater) {
        val callbackType = nativeResolverMethod.parameterTypes.last().toString()
        val callbackClass = mutableClassDefBy(
            EXTENSION_IN_APP_DOWNLOADS_CLASS.removeSuffix(";") + "\$NoOpOfflinePositionCallback;"
        )
        callbackClass.interfaces.add(callbackType)
        val callbackMethod = offlinePositionCallbackFingerprint(callbackType).method
        // The native callback appends diagnostics for each lookup. Keep those entries unchanged
        // when a marked request runs the lookup again after the native branches converge.
        callbackClass.addBridge(
            callbackMethod.name,
            listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            "Ljava/lang/Object;",
            4,
            """
                const/4 v0, 0x0
                return-object v0
            """,
            AccessFlags.PUBLIC.value
        )
        "sget-object v2, ${callbackClass.type}->INSTANCE:${callbackClass.type}"
    } else ""
    // Before 21.23.492, the resolver writes the position into playback state.
    // On 21.23.492+, it returns the position directly.
    val lookupInstructions = if (is_21_23_or_greater) {
        """
            invoke-virtual { v0, p3, p2, v2 }, $nativeResolverMethod
            move-result-object v0
        """
    } else {
        val playbackStateGetter = lookupMatches[2].methodReference
        val positionField = offlinePlaybackStatePositionFingerprint(
            startupMethod,
            playbackStateGetter
        ).instructionMatches.last().fieldReference
        """
            iget-object v1, p1, $contextPlayerField
            invoke-interface { v1 }, $playbackStateGetter
            move-result-object v1
            invoke-virtual { v0, v1${if (is_20_51_or_greater) ", v2" else ""} }, $nativeResolverMethod
            iget-wide v0, v1, $positionField
            invoke-static { v0, v1 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v0
        """
    }
    val resolvePositionMethodName = "patch_resolveOfflineStart"
    val resolvePositionMethod = "${directorClass.type}->$resolvePositionMethodName" +
            "(${directorClass.type}$contextType$playerResponseModelType$descriptorType)Ljava/lang/Long;"
    directorClass.addBridge(
        resolvePositionMethodName,
        listOf(directorClass.type, contextType, playerResponseModelType, descriptorType),
        "Ljava/lang/Long;",
        8,
        """
            invoke-static { p3, p2 }, $shouldResumeMethod
            move-result v0
            if-eqz v0, :skip
            iget-object v0, p0, ${lookupMatches.first().fieldReference}
            $callbackInstructions
            $lookupInstructions
            return-object v0
            :skip
            const/4 v0, 0x0
            return-object v0
        """,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
    )

    // Resolve after the native startup branches converge so the selected position reaches playback.
    // Before 20.51.39, startup overwrites the launch descriptor parameter before this point.
    startupMethod.cloneParameters()
    val playerCreationFingerprint = offlinePlayerCreationFingerprint(startupMethod, playerResponseModelType)
    val playerCreationMatch = playerCreationFingerprint.instructionMatches.single()
    val startRegister = playerCreationMatch.instruction.registersUsed[3]
    playerCreationFingerprint.method.addInstructionsAtControlFlowLabel(
        playerCreationMatch.index,
        """
            invoke-static/range { p0 .. p3 }, $resolvePositionMethod
            move-result-object p0
            if-eqz p0, :resume
            invoke-virtual/range { p0 .. p0 }, Ljava/lang/Long;->longValue()J
            move-result-wide v$startRegister
            :resume
            nop
        """
    )

    val applyPositionInstructions = if (is_21_23_or_greater) {
        val positionUpdateMatches = offlinePlaybackPositionUpdateFingerprint(
            refreshFingerprint.method,
            nativeResolverMethod,
            contextPlayerField.type
        ).instructionMatches
        """
            move-result-object v1
            if-eqz v1, :skip
            invoke-virtual { p0 }, ${positionUpdateMatches[1].methodReference}
            move-result-object v0
            invoke-virtual { v1 }, Ljava/lang/Long;->longValue()J
            move-result-wide v1
            invoke-static { v0, v1, v2 }, ${positionUpdateMatches.last().methodReference}
        """
    } else ""
    directorClass.addBridge(
        resolvePositionMethodName,
        listOf(directorClass.type, contextType, descriptorType),
        "V",
        7,
        """
            iget-object v0, p1, $contextPlayerField
            invoke-interface { v0 }, $playerResponseGetter
            move-result-object v1
            invoke-static { p0, p1, v1, p2 }, $resolvePositionMethod
            $applyPositionInstructions
            :skip
            return-void
        """,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
    )
    refreshFingerprint.method.cloneParameters()
    val applyRefreshFingerprint = offlinePlayerRefreshApplyFingerprint(
        refreshFingerprint.method,
        contextPlayerField.type
    )
    applyRefreshFingerprint.method.addInstructionsAtControlFlowLabel(
        applyRefreshFingerprint.instructionMatches.single().index,
        "invoke-static/range { p0 .. p2 }, ${directorClass.type}->$resolvePositionMethodName" +
                "(${directorClass.type}$contextType$descriptorType)V"
    )
}

private fun preferSavedOfflinePlaybackPosition(
    resolverType: String,
    playerResponseModelType: String,
    descriptorType: String,
    shouldResumeMethod: String,
) {
    val resolverClass = mutableClassDefBy(resolverType)
    val resolverFingerprint = offlinePlaybackPositionResolverFingerprint(
        resolverType,
        playerResponseModelType,
        descriptorType
    )
    val (hasStartMatch, hasStartResultMatch, _, getStartMatch) = resolverFingerprint.instructionMatches
    val fallbackCallMatches = resolverFingerprint.instructionMatches.takeLast(4)
    val nativeFallbackMethod = fallbackCallMatches.first().methodReference
    check(fallbackCallMatches.all { it.methodReference == nativeFallbackMethod })
    val resultRegister = hasStartResultMatch.getInstruction<OneRegisterInstruction>().registerA
    val fallbackMethodName = "patch_resolveOfflineStartFallback"
    val fallbackMethod = "${resolverClass.type}->$fallbackMethodName" +
            "($descriptorType$playerResponseModelType)Ljava/lang/Long;"

    resolverClass.addBridge(
        fallbackMethodName,
        listOf(descriptorType, playerResponseModelType),
        "Ljava/lang/Long;",
        4,
        """
            invoke-static { p0, p1 }, $shouldResumeMethod
            move-result v0
            if-eqz v0, :use_response
            invoke-virtual { p0 }, ${hasStartMatch.methodReference}
            move-result v0
            if-eqz v0, :use_response
            invoke-virtual { p0 }, ${getStartMatch.methodReference}
            move-result-wide v0
            invoke-static { v0, v1 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v0
            return-object v0
            :use_response
            invoke-static { p1 }, $nativeFallbackMethod
            move-result-object v0
            return-object v0
        """,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
    )

    // On 21.23.492+, the resolver returns the command's explicit start before reading saved progress.
    // For a marked card, use that time only when the resolver falls back to the response's start.
    fallbackCallMatches.forEach {
        resolverFingerprint.method.replaceInstruction(it.index, "invoke-static/range { p1 .. p2 }, $fallbackMethod")
    }
    resolverFingerprint.method.addInstructionsWithLabels(
        hasStartResultMatch.index + 1,
        """
            if-eqz v$resultRegister, :resume
            invoke-static/range { p1 .. p2 }, $shouldResumeMethod
            move-result v$resultRegister
            xor-int/lit8 v$resultRegister, v$resultRegister, 0x1
            :resume
            nop
        """
    )
}
