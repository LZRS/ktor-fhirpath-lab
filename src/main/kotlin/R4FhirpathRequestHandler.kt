package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.model.r4.CodeableConcept
import com.google.fhir.model.r4.Enumeration
import com.google.fhir.model.r4.Extension
import com.google.fhir.model.r4.FhirDate
import com.google.fhir.model.r4.FhirR4Json
import com.google.fhir.model.r4.OperationOutcome
import com.google.fhir.model.r4.Parameters
import com.google.fhir.model.r4.Resource
import com.google.fhir.model.r4.String as FhirString
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText

class R4FhirpathRequestHandler: RequestHandler {
    private val parser = FhirR4Json()

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
        val parameterVariables =
            parameters.parameter.find { it.name.value == "variables" }?.part
                ?.mapNotNull { parameter ->
                    val name = parameter.name.value
                    val value = parameter.value.let { value ->
                        when (value) {
                            is Parameters.Parameter.Value.Boolean -> value.value.value
                            is Parameters.Parameter.Value.String -> value.value.value
                            is Parameters.Parameter.Value.Integer -> value.value.value
                            is Parameters.Parameter.Value.Decimal -> value.value.value
                            is Parameters.Parameter.Value.Date -> value.value.value
                            is Parameters.Parameter.Value.Time -> value.value.value
                            is Parameters.Parameter.Value.DateTime -> value.value.value
                            else -> null
                        }
                    } ?: parameter.resource
                    if (name != null && value != null) {
                        name to value
                    } else null
                }

        val resourceParameter = parameters.parameter.find { it.name.value == "resource" }
        if (resourceParameter == null) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Invalid), message = "Cannot evaluate without a fhirpath expression")
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }
        val resource = resourceParameter.resource as? Resource
        val variables = buildMap {
            put("resource", resource)
            put("rootResource", resource)
            if (contextStr != null) {
                put("context", contextStr)
            }
            if (parameterVariables != null) {
                putAll(parameterVariables)
            }
        }

        val engine = FhirPathEngine.forR4()
        val results = try {
            engine.evaluateExpression(expressionStr, resource, variables)
        } catch (e: Exception) {
            val operationOutcome = makeOperationOutcome(code = Enumeration(value = OperationOutcome.IssueType.Exception), message = e.message)
            call.respond(HttpStatusCode.BadRequest, parser.encodeToString(operationOutcome))
            return
        }

        val responseParameters = Parameters(
            id =  "fhirpath",
            parameter = listOf(
                Parameters.Parameter(
                    name = FhirString(value = "parameters"),
                    part = buildList {
                        add(Parameters.Parameter(
                            name = FhirString(value = "evaluator"),
                            value = Parameters.Parameter.Value.String(value = FhirString(value = "Kotlin FHIRPath (R4)"))
                        ))
                        add(Parameters.Parameter(
                            name = FhirString(value = "expression"),
                            value = Parameters.Parameter.Value.String(value = FhirString(value = expressionStr))
                        ))
                        if (contextStr != null) add(
                            Parameters.Parameter(
                                name = FhirString(value = "context"),
                                value = Parameters.Parameter.Value.String(value = FhirString(value = contextStr))
                            ))
                    }
                ),
                Parameters.Parameter(
                    name = FhirString(value = "result"),
                    part = buildList {
                        addAll(results.map { makeParameter(it) })
                    }
                )
            ),
        )
        call.respondText(parser.encodeToString(responseParameters), ContentType.Application.Json)
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

    private fun makeParameter(itemValue: Any): Parameters.Parameter {
        return Parameters.Parameter(
            name = FhirString(value = itemValue::class.simpleName?.lowercase()),
            value = when(itemValue) {
                is String -> Parameters.Parameter.Value.String(value = FhirString(value = itemValue))
                else -> TODO()
            }
        )
    }
}