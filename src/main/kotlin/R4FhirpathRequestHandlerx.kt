package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.fhirpath.types.FhirPathDateTime
import com.google.fhir.fhirpath.types.FhirPathQuantity
import com.google.fhir.fhirpath.types.FhirPathTime
import com.google.fhir.model.r4.CodeableConcept
import com.google.fhir.model.r4.Enumeration
import com.google.fhir.model.r4.FhirDate
import com.google.fhir.model.r4.FhirDateTime as R4FhirDateTime
import com.google.fhir.model.r4.FhirR4Json
import com.google.fhir.model.r4.OperationOutcome
import com.google.fhir.model.r4.Parameters
import com.google.fhir.model.r4.Resource
import com.google.fhir.model.r4.String as FhirString
import com.google.fhir.model.r4.Time
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.datetime.LocalTime

/**
 * FHIRPath evaluation handler for FHIR R4 / R4B.
 *
 * Mirrors fhirpath-py-server's `fhirpath.py`, specifically:
 *   - `parse_request_data`    → parsing the incoming FHIR Parameters body
 *   - `create_parameters`     → driving evaluation with optional context sub-path
 *   - `node_results_to_types` → mapping FHIRPath result types to Parameters entries
 *   - `handle_fhirpath`       → orchestration + error handling
 *
 * The engine converts all primitive return values to FHIRPath system types before
 * returning them, so the result collection contains:
 *   kotlin.String, kotlin.Boolean, kotlin.Int, kotlin.Long,
 *   BigDecimal (ionspin), FhirPathDateTime, FhirPathTime,
 *   FhirPathQuantity, or any remaining complex FHIR element.
 *
 * Note: FhirPathDate is an `internal` type in the library. We detect it by its
 * qualified class name and access its string representation via toString().
 */
class R4FhirpathRequestHandlerx : RequestHandler {

    private val engine = FhirPathEngine.forR4()
    private val parser = FhirR4Json()

    // ── handle (mirrors handle_fhirpath) ─────────────────────────────────────

    override suspend fun handle(call: ApplicationCall) {
        val content = call.receiveText()

        val inputParameters = try {
            parser.decodeFromString(content) as Parameters
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadRequest,
                OperationOutcome.IssueType.Exception,
                "Failed to parse request body as FHIR Parameters: ${e.message}",
            )
            return
        }

        // ── parse_request_data ────────────────────────────────────────────────
        var expressionStr: kotlin.String? = null
        var resource: Resource? = null
        var contextStr: kotlin.String? = null
        var terminologyServer: kotlin.String? = null
        val variables = mutableMapOf<kotlin.String, Any?>()

        for (param in inputParameters.parameter) {
            when (param.name.value) {
                "expression"        -> expressionStr = param.value?.asString()?.value?.value
                "resource"          -> resource = param.resource as? Resource
                "context"           -> contextStr = param.value?.asString()?.value?.value
                "terminologyserver" -> terminologyServer = param.value?.asString()?.value?.value
                "variables"         -> {
                    for (part in param.part) {
                        val name = part.name.value ?: continue
                        val value: Any? = when (val v = part.value) {
                            is Parameters.Parameter.Value.String   -> v.value.value
                            is Parameters.Parameter.Value.Boolean  -> v.value.value
                            is Parameters.Parameter.Value.Integer  -> v.value.value
                            is Parameters.Parameter.Value.Decimal  -> v.value.value
                            is Parameters.Parameter.Value.Date     -> v.value.value
                            is Parameters.Parameter.Value.Time     -> v.value.value
                            is Parameters.Parameter.Value.DateTime -> v.value.value
                            else                                   -> part.resource
                        }
                        variables[name] = value
                    }
                }
            }
        }

        // ── validate ──────────────────────────────────────────────────────────
        if (expressionStr.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.BadRequest,
                OperationOutcome.IssueType.Incomplete,
                "Cannot evaluate without a FHIRPath expression",
            )
            return
        }
        if (resource == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                OperationOutcome.IssueType.Invalid,
                "Cannot evaluate without a resource",
            )
            return
        }

        // Always expose %resource and %rootResource (mirrors py server)
        variables["resource"]     = resource
        variables["rootResource"] = resource

        // ── create_parameters + evaluate ─────────────────────────────────────
        val responseParameters = try {
            buildResponseParameters(expressionStr, resource, contextStr, terminologyServer, variables)
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.InternalServerError,
                OperationOutcome.IssueType.Exception,
                "FHIRPath evaluation error: ${e.message}",
            )
            return
        }

        call.respondText(
            parser.encodeToString(responseParameters),
            ContentType.Application.Json,
        )
    }

    // ── create_parameters ────────────────────────────────────────────────────

    /**
     * Builds the response Parameters resource, mirroring `create_parameters` in fhirpath.py.
     *
     * When [contextStr] is provided the expression is evaluated against every node selected by
     * the context path, producing one `result` part per context node (with a `valueString` label
     * matching `ResourceType.context[index]`).
     */
    private fun buildResponseParameters(
        expressionStr: kotlin.String,
        resource: Resource,
        contextStr: kotlin.String?,
        terminologyServer: kotlin.String?,
        variables: Map<kotlin.String, Any?>,
    ): Parameters {

        val resourceTypeName = resource::class.simpleName ?: ""

        val resultParts: List<Parameters.Parameter> = if (contextStr != null) {
            val contextNodes = engine.evaluateExpression(contextStr, resource, variables)
            contextNodes.mapIndexed { index, contextNode ->
                val contextLabel = "$resourceTypeName.$contextStr[$index]"
                val contextVariables = variables + mapOf(
                    "resource"     to contextNode,
                    "rootResource" to resource,
                )
                val nodeResults = engine.evaluateExpression(
                    expressionStr,
                    contextNode as? Resource,
                    contextVariables,
                )
                Parameters.Parameter(
                    name = FhirString(value = "result"),
                    value = Parameters.Parameter.Value.String(value = FhirString(value = contextLabel)),
                    part = nodeResultsToParameters(nodeResults),
                )
            }
        } else {
            val nodeResults = engine.evaluateExpression(expressionStr, resource, variables)
            listOf(
                Parameters.Parameter(
                    name = FhirString(value = "result"),
                    part = nodeResultsToParameters(nodeResults),
                )
            )
        }

        // Echo block (mirrors the `parameters` part in the py server response)
        val echoParam = Parameters.Parameter(
            name = FhirString(value = "parameters"),
            part = buildList {
                add(Parameters.Parameter(
                    name = FhirString(value = "evaluator"),
                    value = Parameters.Parameter.Value.String(
                        value = FhirString(value = "Kotlin FHIRPath (R4)")
                    ),
                ))
                if (contextStr != null) add(Parameters.Parameter(
                    name = FhirString(value = "context"),
                    value = Parameters.Parameter.Value.String(value = FhirString(value = contextStr)),
                ))
                add(Parameters.Parameter(
                    name = FhirString(value = "expression"),
                    value = Parameters.Parameter.Value.String(value = FhirString(value = expressionStr)),
                ))
                add(Parameters.Parameter(
                    name = FhirString(value = "resource"),
                    resource = resource,
                ))
                if (terminologyServer != null) add(Parameters.Parameter(
                    name = FhirString(value = "terminologyServerUrl"),
                    value = Parameters.Parameter.Value.String(
                        value = FhirString(value = terminologyServer)
                    ),
                ))
                val extraVars = variables.entries
                    .filter { it.key !in setOf("resource", "rootResource", "context") }
                if (extraVars.isNotEmpty()) {
                    add(Parameters.Parameter(
                        name = FhirString(value = "variables"),
                        part = extraVars.mapNotNull { (k, v) ->
                            val strVal = v?.toString() ?: return@mapNotNull null
                            Parameters.Parameter(
                                name = FhirString(value = k),
                                value = Parameters.Parameter.Value.String(
                                    value = FhirString(value = strVal)
                                ),
                            )
                        },
                    ))
                }
            },
        )

        return Parameters(
            id = "fhirpath",
            parameter = listOf(echoParam) + resultParts,
        )
    }

    // ── node_results_to_types ────────────────────────────────────────────────

    /**
     * Maps FHIRPath system types returned by [FhirPathEngine.evaluateExpression] to
     * FHIR Parameters.Parameter entries, mirroring `node_results_to_types` in fhirpath.py.
     *
     * The engine's `toFhirPathType` converts FHIR primitives before returning, so:
     *   FHIR string/uri/code/id/uuid/oid/markdown/base64 → kotlin.String
     *   FHIR boolean     → kotlin.Boolean
     *   FHIR integer/…   → kotlin.Int
     *   FHIR decimal     → BigDecimal (ionspin)
     *   FHIR date        → FhirPathDate (internal — detected by class name)
     *   FHIR dateTime    → FhirPathDateTime
     *   FHIR time        → FhirPathTime
     *   FHIR Quantity    → FhirPathQuantity
     *   everything else  → raw FHIR element/resource
     */
    private fun nodeResultsToParameters(results: Collection<Any>): List<Parameters.Parameter> =
        results.map { fhirPathItemToParameter(it) }

    private fun fhirPathItemToParameter(item: Any): Parameters.Parameter = when {

        item is kotlin.String -> Parameters.Parameter(
            name = FhirString(value = "string"),
            value = Parameters.Parameter.Value.String(value = FhirString(value = item)),
        )

        item is kotlin.Boolean -> Parameters.Parameter(
            name = FhirString(value = "boolean"),
            value = Parameters.Parameter.Value.Boolean(
                value = com.google.fhir.model.r4.Boolean(value = item)
            ),
        )

        item is kotlin.Int -> Parameters.Parameter(
            name = FhirString(value = "integer"),
            value = Parameters.Parameter.Value.Integer(
                value = com.google.fhir.model.r4.Integer(value = item)
            ),
        )

        // Long: no valueInteger64 in R4; represent as string
        item is kotlin.Long -> Parameters.Parameter(
            name = FhirString(value = "integer64"),
            value = Parameters.Parameter.Value.String(
                value = FhirString(value = item.toString())
            ),
        )

        item is BigDecimal -> Parameters.Parameter(
            name = FhirString(value = "decimal"),
            value = Parameters.Parameter.Value.Decimal(
                value = com.google.fhir.model.r4.Decimal(
                    value = item
                )
            ),
        )

        // FhirPathDate is `internal` — detect by qualified class name and convert via toString()
        item::class.qualifiedName == "com.google.fhir.fhirpath.types.FhirPathDate" -> Parameters.Parameter(
            name = FhirString(value = "date"),
            value = Parameters.Parameter.Value.Date(
                value = com.google.fhir.model.r4.Date(
                    value = parseFhirDate(item.toString())
                )
            ),
        )

        item is FhirPathDateTime -> Parameters.Parameter(
            name = FhirString(value = "dateTime"),
            value = Parameters.Parameter.Value.DateTime(
                value = com.google.fhir.model.r4.DateTime(
                    value = parseFhirDateTime(item.toString())
                )
            ),
        )

        item is FhirPathTime -> Parameters.Parameter(
            name = FhirString(value = "time"),
            value = Parameters.Parameter.Value.Time(
                value = Time(
                    value = parseLocalTime(item)
                )
            ),
        )

        item is FhirPathQuantity -> Parameters.Parameter(
            name = FhirString(value = "Quantity"),
            value = Parameters.Parameter.Value.Quantity(
                value = com.google.fhir.model.r4.Quantity(
                    value = com.google.fhir.model.r4.Decimal(
                        value = item.value
                    ),
                    unit = FhirString(value = item.unit),
                )
            ),
        )

        // Complex FHIR resource — embed directly
        item is Resource -> Parameters.Parameter(
            name = FhirString(value = item::class.simpleName?.lowercase() ?: "resource"),
            resource = item,
        )

        // Fallback: any other complex element (mirrors py server's json.dumps fallback)
        else -> Parameters.Parameter(
            name = FhirString(value = item::class.simpleName?.lowercase() ?: "unknown"),
            value = Parameters.Parameter.Value.String(
                value = FhirString(value = item.toString())
            ),
        )
    }

    // ── date/time parsing helpers ─────────────────────────────────────────────

    /** Parses a FHIRPath date string (e.g. "2024", "2024-03", "2024-03-15") into a [FhirDate]. */
    private fun parseFhirDate(str: kotlin.String): FhirDate? =
        runCatching { FhirDate.fromString(str) }.getOrNull()

    /** Parses a FHIRPath dateTime string into a [R4FhirDateTime]. */
    private fun parseFhirDateTime(str: kotlin.String): R4FhirDateTime? =
        runCatching { R4FhirDateTime.fromString(str) }.getOrNull()

    /** Converts a [FhirPathTime] to a [LocalTime]. */
    private fun parseLocalTime(t: FhirPathTime): LocalTime = LocalTime(
        hour   = t.hour,
        minute = t.minute ?: 0,
        second = t.second?.toInt() ?: 0,
        nanosecond = t.second?.let { s ->
            ((s - s.toInt()) * 1_000_000_000).toInt()
        } ?: 0,
    )

    // ── error helper ──────────────────────────────────────────────────────────

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        issueType: OperationOutcome.IssueType,
        message: kotlin.String,
    ) {
        val outcome = OperationOutcome(
            issue = listOf(
                OperationOutcome.Issue(
                    severity = Enumeration(value = OperationOutcome.IssueSeverity.Error),
                    code = Enumeration(value = issueType),
                    details = CodeableConcept(text = FhirString(value = message)),
                )
            )
        )
        respondText(
            parser.encodeToString(outcome),
            ContentType.Application.Json,
            status,
        )
    }
}