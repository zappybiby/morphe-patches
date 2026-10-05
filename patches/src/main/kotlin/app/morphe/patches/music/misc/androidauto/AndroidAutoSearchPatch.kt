/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SEARCH_PATCH_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch;"
private const val PHONE_SEARCH_CLIENT =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$PhoneSearchClient;"
private const val PHONE_SEARCH_RESPONSE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$PhoneSearchResponse;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val EXECUTOR = "Ljava/util/concurrent/Executor;"

// region Phone search client and responses

/** Identifies the query field in a phone search request. */
private object PhoneSearchRequestFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("query", "params", "musicSearchRequestType"),
    filters = listOf(
        string("query"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;", location = MatchAfterWithin(2)),
    ),
)

/** Finds the calls YTM uses to create and send a phone search. */
private object PhoneSearchSubmissionFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("executeInnerTubeSearch", "Could not parse searchbox stats"),
)

/** Connects YTM's phone online search client to Android Auto's network-search path. */
internal fun BytecodePatchContext.patchSearch() {
    val requestType = PhoneSearchRequestFingerprint.originalClassDef.type
    val phoneSearchSubmissionMethod = PhoneSearchSubmissionFingerprint.originalMethod
    val submissionCalls = phoneSearchSubmissionMethod.instructions.mapNotNull { it.getReference<MethodReference>() }.distinct()
    val createSearchRequestMethod = submissionCalls.single { it.returnType == requestType && it.parameterTypes.isEmpty() }
    val clientClass = mutableClassDefBy(createSearchRequestMethod.definingClass)

    fun hierarchy(type: String): Set<String> = buildSet {
        var current: String? = type
        while (current != null && add(current)) current = classDefByOrNull(current)?.superclass
    }

    val requestHierarchy = hierarchy(requestType)
    val submitSearchMethodReference = submissionCalls.single { call ->
        call.returnType == "V" && call.parameterTypes.size == 3 &&
                call.parameterTypes.first().toString() in requestHierarchy &&
                clientClass.methods.any {
                    it.parameterTypes.isEmpty() && it.returnType == call.parameterTypes.last().toString()
                }
    }
    val policyType = submitSearchMethodReference.parameterTypes.last().toString()
    val requestPolicyMethod = clientClass.methods.single { it.parameterTypes.isEmpty() && it.returnType == policyType }
    val requestSenderField = clientClass.instanceFields.single { submitSearchMethodReference.definingClass in hierarchy(it.type) }
    val requestSenderClass = classDefBy(submitSearchMethodReference.definingClass)
    val submitSearchMethod = requestSenderClass.methods.single {
        it.name == submitSearchMethodReference.name && it.parameterTypes == submitSearchMethodReference.parameterTypes
    }
    val responseConverterField = submitSearchMethod.instructions.mapNotNull {
        if (it.opcode == Opcode.SGET_OBJECT) it.getReference<FieldReference>() else null
    }.single()
    // Use YTM's future-returning request method so Auto can handle completion and timeouts.
    val submitSearchFutureMethod = requestSenderClass.methods.single {
        it.returnType == FUTURE && it.parameterTypes.map(CharSequence::toString) == listOf(
            submitSearchMethodReference.parameterTypes.first().toString(), responseConverterField.type, EXECUTOR, policyType,
        )
    }

    val queryField = PhoneSearchRequestFingerprint.instructionMatches.mapNotNull {
        it.instruction.getReference<FieldReference>()
    }.single()
    val requestClass = classDefBy(requestType)
    // Search YTM's online content; its request builder leaves the search type unspecified.
    val onlineSearchTypeMatches = requestClass.instanceFields.mapNotNull { field ->
        if (!field.type.startsWith("L")) return@mapNotNull null
        val enumClass = classDefByOrNull(field.type) ?: return@mapNotNull null
        val classInitializer = enumClass.methods.singleOrNull { it.name == "<clinit>" } ?: return@mapNotNull null
        val instructions = classInitializer.instructions.toList()
        val catalogTypeNameIndex = instructions.indexOfFirst {
            it.getReference<StringReference>()?.string == "MUSIC_SEARCH_REQUEST_TYPE_CATALOG"
        }
        if (catalogTypeNameIndex < 0) return@mapNotNull null
        val onlineSearchValueField = instructions.drop(catalogTypeNameIndex + 1).first { it.opcode == Opcode.SPUT_OBJECT }
            .getReference<FieldReference>()!!
        field to onlineSearchValueField
    }
    val (requestTypeField, onlineSearchValueField) = onlineSearchTypeMatches.single()

    clientClass.interfaces.add(PHONE_SEARCH_CLIENT)
    clientClass.addInterfaceMethod(
        extensionInterfaceMethod(PHONE_SEARCH_CLIENT, "patch_search"),
        8,
        """
            invoke-virtual { p0 }, $createSearchRequestMethod
            move-result-object v1
            iput-object p1, v1, $queryField
            sget-object v0, $onlineSearchValueField
            iput-object v0, v1, $requestTypeField
            invoke-virtual { p0 }, $requestPolicyMethod
            move-result-object v4
            iget-object v0, p0, $requestSenderField
            sget-object v2, $responseConverterField
            move-object v3, p2
            invoke-virtual/range { v0 .. v4 }, $submitSearchFutureMethod
            move-result-object v0
            return-object v0
        """,
    )

    val decodeSearchResponseMethod = classDefBy(requestSenderField.type).methods.single { method ->
        method.parameterTypes.singleOrNull()?.toString() == "Lcom/google/protobuf/MessageLite;" &&
                method.returnType == "Ljava/lang/Object;"
    }
    val responseType = decodeSearchResponseMethod.instructions.single { it.opcode == Opcode.NEW_INSTANCE }
        .getReference<TypeReference>()!!.type
    val responseConstructor = decodeSearchResponseMethod.instructions.mapNotNull { it.getReference<MethodReference>() }
        .single { it.definingClass == responseType && it.name == "<init>" }
    val responseMessageType = responseConstructor.parameterTypes.single().toString()
    val responseClass = mutableClassDefBy(responseType)
    val responseMessageField = responseClass.instanceFields.single { it.type == responseMessageType }
    responseClass.interfaces.add(PHONE_SEARCH_RESPONSE)
    responseClass.addInterfaceMethod(
        extensionInterfaceMethod(PHONE_SEARCH_RESPONSE, "patch_searchResponseBytes"),
        1,
        """
            iget-object p0, p0, $responseMessageField
            invoke-virtual { p0 }, $responseMessageType->toByteArray()[B
            move-result-object p0
            return-object p0
        """,
    )
    hookPhoneClientOnServiceCreate(
        clientClass.type, PHONE_SEARCH_CLIENT,
        "$SEARCH_PATCH_CLASS->setPhoneSearchClient($PHONE_SEARCH_CLIENT)V",
    )
    hookAndroidAutoSearchRequests()
}

// endregion

// region Android Auto search requests

/** Returns a completed list of search results to Android Auto. */
private object AndroidAutoSearchResultDeliveryFingerprint : Fingerprint(
    parameters = listOf("Ljava/util/List;"), returnType = "V",
    strings = listOf("MBS: SearchResult.sendResult() called multiple times."),
)

/** YTM's ContentSupplier handles requests for pages and search results shown in Android Auto. */
private object ContentHandlerFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("com/google/android/apps/youtube/music/mediabrowser/content/ContentSupplier"),
)

/** Replaces Android Auto's online search with the phone app's search across YouTube Music. */
private fun BytecodePatchContext.hookAndroidAutoSearchRequests() {
    val deliverSearchResultsMethod = AndroidAutoSearchResultDeliveryFingerprint.method
    val searchRequestClass = mutableClassDefBy(deliverSearchResultsMethod.definingClass)
    val searchRequestInterface = $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$AndroidAutoSearchRequest;"
    val queryField = searchRequestClass.instanceFields.single { it.type == "Ljava/lang/String;" }
    searchRequestClass.interfaces.add(searchRequestInterface)
    searchRequestClass.addInterfaceMethod(extensionInterfaceMethod(searchRequestInterface, "patch_query"), 1, """
        iget-object p0, p0, $queryField
        return-object p0
    """)
    searchRequestClass.addInterfaceMethod(extensionInterfaceMethod(searchRequestInterface, "patch_deliverSearchResults"), 2, """
        invoke-virtual { p0, p1 }, $deliverSearchResultsMethod
        return-void
    """)
    val contentHandlerClass = mutableClassDefBy(ContentHandlerFingerprint.originalMethod.definingClass)
    val handleSearchRequestMethod = contentHandlerClass.methods.single {
        it.returnType == "V" && it.parameterTypes == listOf(searchRequestClass.type)
    }
    // Keep YTM's local Library search and caller checks; replace the online search that follows.
    val instructions = handleSearchRequestMethod.instructions.toList()
    val callerContextReadIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.let {
                    it.definingClass == searchRequestClass.type && it.type != "Ljava/lang/String;"
                } == true
    }
    // Insert after YTM's caller check and before it reads the next field to build the network request.
    val networkSearchStartIndex = instructions.withIndex().first { (index, instruction) ->
        index > callerContextReadIndex && instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.definingClass == searchRequestClass.type
    }.index
    // Existing jumps to this instruction must also run the hook before YTM sends the request.
    val hookResultRegister = handleSearchRequestMethod.findFreeRegister(networkSearchStartIndex)
    handleSearchRequestMethod.addInstructionsAtControlFlowLabel(networkSearchStartIndex, """
        invoke-static { p1 }, $SEARCH_PATCH_CLASS->handleSearch($searchRequestInterface)Z
        move-result v$hookResultRegister
        if-eqz v$hookResultRegister, :native_search
        return-void
        :native_search
        nop
    """)
    // Offline mode can route the submitted query through Library search before the online hook runs.
    // Give that native request a deadline as well, and ignore callbacks arriving after completion.
    handleSearchRequestMethod.addInstructions(0, """
        invoke-static { p1 }, $SEARCH_PATCH_CLASS->onSearchRequest($searchRequestInterface)V
    """)
    val completionRegister = deliverSearchResultsMethod.findFreeRegister(0)
    deliverSearchResultsMethod.addInstructions(0, """
        invoke-static { p0 }, $SEARCH_PATCH_CLASS->onSearchResult($searchRequestInterface)Z
        move-result v$completionRegister
        if-nez v$completionRegister, :deliver_results
        return-void
        :deliver_results
        nop
    """)
}

// endregion
