package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.model.r5.FhirR5Json
import com.google.fhir.model.r5.Parameters
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

class R5RequestHandler: RequestHandler {
    override suspend fun handle(call: ApplicationCall) {
        val content = call.receiveText()
        val parser = FhirR5Json()
        val parameters = try {
            parser.decodeFromString(content)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Failed to parse Parameters: ${e.message}")
            return
        }

        val expressionParam = parameters.parameter.find { it.name.value == "expression" }
        val expressionStr = when (val valueX = expressionParam?.value) {
            is R5Parameters.Parameter.Value.String -> valueX.value.value
            else -> null
        }

        if (expressionStr.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Cannot evaluate without a fhirpath expression")
            return
        }

        val contextParam = parameters.parameter.find { it.name.value == "context" }
        val contextStr = when (val valueX = contextParam?.value) {
            is R5Parameters.Parameter.Value.String -> valueX.value.value
            else -> null
        }

        val resourceParam = parameters.parameter.find { it.name.value == "resource" }
        val resource = resourceParam?.resource

        if (resource == null) {
            call.respond(HttpStatusCode.BadRequest, "Cannot evaluate without a test resource")
            return
        }

        val engine = FhirPathEngine.forR5()
        val results = try {
            engine.evaluateExpression(expressionStr, resource)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Error evaluating fhirpath expression: ${e.message}")
            return
        }

        val responseParams = R5Parameters.Builder().apply {
            id = com.google.fhir.model.r5.Id.Builder().apply { value = "fhirpath" }.build()
            parameter.add(
                R5Parameters.Parameter.Builder().apply {
                    name = com.google.fhir.model.r5.String.Builder().apply { value = "parameters" }.build()
                    part.add(
                        R5Parameters.Parameter.Builder().apply {
                            name = com.google.fhir.model.r5.String.Builder().apply { value = "evaluator" }.build()
                            value = R5Parameters.Parameter.Value.String(
                                com.google.fhir.model.r5.String.Builder().apply { value = "Kotlin FHIRPath R5" }.build()
                            )
                        }.build()
                    )
                    part.add(
                        R5Parameters.Parameter.Builder().apply {
                            name = com.google.fhir.model.r5.String.Builder().apply { value = "expression" }.build()
                            value = R5Parameters.Parameter.Value.String(
                                com.google.fhir.model.r5.String.Builder().apply { value = expressionStr }.build()
                            )
                        }.build()
                    )
                    if (contextStr != null) {
                        part.add(
                            R5Parameters.Parameter.Builder().apply {
                                name = com.google.fhir.model.r5.String.Builder().apply { value = "context" }.build()
                                value = R5Parameters.Parameter.Value.String(
                                    com.google.fhir.model.r5.String.Builder().apply { value = contextStr }.build()
                                )
                            }.build()
                        )
                    }

                    results.forEach { result ->
                        part.add(
                            R5Parameters.Parameter.Builder().apply {
                                name = com.google.fhir.model.r5.String.Builder().apply { value = "result" }.build()
                                value = R5Parameters.Parameter.Value.String(
                                    com.google.fhir.model.r5.String.Builder().apply { value = result.toString() }.build()
                                )
                            }.build()
                        )
                    }
                }.build()
            )
        }.build()

        call.respondText(parser.encodeToString(responseParams), ContentType.Application.Json)
    }
}