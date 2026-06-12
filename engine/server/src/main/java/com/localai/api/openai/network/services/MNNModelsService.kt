package com.localai.api.openai.network.services

import com.localai.api.openai.network.logging.ChatLogger
import com.localai.api.openai.network.models.ModelData
import com.localai.api.openai.network.models.ModelPermission
import com.localai.api.openai.network.models.ModelsResponse
import com.localai.api.openai.manager.CurrentModelManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import timber.log.Timber

class MNNModelsService {
    private val logger = ChatLogger()

    suspend fun getAvailableModels(call: io.ktor.server.application.ApplicationCall, traceId: String) {
        try {
            logger.logRequestStart(traceId, call)
            val currentModelId = CurrentModelManager.getCurrentModelId()
            if (currentModelId == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No current model available"))
                return
            }
            val response = ModelsResponse(data = listOf(
                ModelData(
                    id = currentModelId,
                    created = System.currentTimeMillis() / 1000,
                    permission = listOf(ModelPermission(id = "modelperm-$currentModelId", created = System.currentTimeMillis() / 1000))
                )
            ))
            call.respond(response)
        } catch (e: Exception) {
            Timber.tag("MNNModelsService").e(e, "Error getting current model")
            logger.logError(traceId, e, "Failed to get current model")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get current model"))
        }
    }
}
