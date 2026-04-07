package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.model.r4b.FhirR4bJson
import com.google.fhir.model.r5.FhirR5Json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Clock
import com.google.fhir.model.r4b.OperationOutcome as R4bOperationOutcome
import com.google.fhir.model.r4b.Parameters as R4bParameters
import com.google.fhir.model.r5.Parameters as R5Parameters

fun Application.configureRouting() {
    install(AutoHeadResponse)
    install(DoubleReceive)

    routing {
        get("/") {
            call.respond(mapOf(
                "message" to "FHIR Path API is running!",
                "endpoints" to mapOf(
                    "/health" to "GET - Health check",
                    "/fhir/fhirpath" to "POST - Evaluate R4 and R4B FHIRPath expressions",
                    "/fhir/fhirpath-r5" to "POST - Evaluate R5 FHIRPath expressions",
                )
            ))
        }

        get("/health") {
            call.respond(mapOf(
                "status" to "healthy",
                "timestamp" to Clock.System.now().toString(),
            ))
        }

        route("/fhir") {
            post("/\$fhirpath") { handleFhirpathR4b(call) }
            post("/\$fhirpath-r5") { handleFhirpathR5(call) }
        }
    }
}

suspend fun handleFhirpathR4b(call: ApplicationCall) {}

suspend fun handleFhirpathR5(call: ApplicationCall) {
    val content = call.receiveText()
    val parser = FhirR5Json()
    val parameters = try {
        parser.decodeFromString(content) as R5Parameters
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
