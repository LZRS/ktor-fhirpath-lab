package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.model.r4.CodeableConcept
import com.google.fhir.model.r4.Enumeration
import com.google.fhir.model.r4.FhirR4Json
import com.google.fhir.model.r4.OperationOutcome
import com.google.fhir.model.r4.Parameters
import com.google.fhir.model.r4.String as FhirString
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

class R4FhirpathRequestHandler: RequestHandler {
    val parser = FhirR4Json()

    override suspend fun handle(call: ApplicationCall) {
        val content = call.receiveText()
        val parameters = try {
            parser.decodeFromString(content) as Parameters
        } catch (e: Exception) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Exception), message = e.message)
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }

        val expressionStr = parameters.parameter.find { it.name.value == "expression" }?.value?.asString()?.value?.value
        if (expressionStr.isNullOrBlank()) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Incomplete), message = "Cannot evaluate without a fhirpath expression")
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }

        val contextStr = parameters.parameter.find { it.name.value == "context" }?.value?.asString()?.value?.value
        val variables = parameters.parameter.find { it.name.value == "variables" }?.value

        val resource = parameters.parameter.find { it.name.value == "resource" }?.resource
        if (resource == null) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Invalid), message = "Cannot evaluate without a fhirpath expression")
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }

        val engine = FhirPathEngine.forR4()
        val results = try {
            engine.evaluateExpression(expressionStr, resource)
        } catch (e: Exception) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Exception), message = e.message)
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }

        Parameters(
            id =  "fhirpath",
            parameter = listOf(
                Parameters.Parameter(
                    name = FhirString(value = "parameters"),
                    part = listOf(
                        Parameters.Parameter(
                            name = FhirString(value = "evaluator"),
                            value = Parameters.Parameter.Value.String(value = FhirString(value = "Kotlin FHIRPath R4"))
                        ),
                        Parameters.Parameter(
                            name = FhirString(value = "expression"),
                            value = Parameters.Parameter.Value.String(value = FhirString(value = expressionStr))
                        )
                    ) + if (contextStr != null) listOf(
                        Parameters.Parameter(
                            name = FhirString(value = "context"),
                            value = Parameters.Parameter.Value.String(value = FhirString(value = contextStr))
                        )
                    ) else emptyList()
                )
            ),
            
        )

        val responseParams = Parameters.Builder().apply {
            id = com.google.fhir.model.r4.Id.Builder().apply { value = "" }.build()
            parameter.add(
                Parameters.Parameter.Builder().apply {
                    name = com.google.fhir.model.r4.String.Builder().apply { value = "parameters" }.build()
                    part.add(
                        Parameters.Parameter.Builder().apply {
                            name = com.google.fhir.model.r4.String.Builder().apply { value = "evaluator" }.build()
                            value = Parameters.Parameter.Value.String(
                                com.google.fhir.model.r4.String.Builder().apply { value = "Kotlin FHIRPath R4" }.build()
                            )
                        }.build()
                    )
                    part.add(
                        Parameters.Parameter.Builder().apply {
                            name = com.google.fhir.model.r4.String.Builder().apply { value = "expression" }.build()
                            value = Parameters.Parameter.Value.String(
                                com.google.fhir.model.r4.String.Builder().apply { value = expressionStr }.build()
                            )
                        }.build()
                    )
                    if (contextStr != null) {
                        part.add(
                            Parameters.Parameter.Builder().apply {
                                name = com.google.fhir.model.r4.String.Builder().apply { value = "context" }.build()
                                value = Parameters.Parameter.Value.String(
                                    com.google.fhir.model.r4.String.Builder().apply { value = contextStr }.build()
                                )
                            }.build()
                        )
                    }

                    results.forEach { result ->
                        part.add(
                            Parameters.Parameter.Builder().apply {
                                name = com.google.fhir.model.r4.String.Builder().apply { value = "result" }.build()
                                value = Parameters.Parameter.Value.String(
                                    com.google.fhir.model.r4.String.Builder().apply { value = result.toString() }.build()
                                )
                            }.build()
                        )
                    }
                }.build()
            )
        }.build()

        call.respondText(parser.encodeToString(responseParams), ContentType.Application.Json)
    }

    private fun makeOperationOutcome(code: Enumeration<OperationOutcome.IssueType>, message: String?): OperationOutcome = OperationOutcome(
        issue = listOf(
            OperationOutcome.Issue(
                severity = Enumeration(value = OperationOutcome.IssueSeverity.Error),
                code = code,
                details = CodeableConcept(text = FhirString(value = message))
            )
        )
    )
}