package dev.ohs

import com.google.fhir.fhirpath.FhirPathEngine
import com.google.fhir.fhirpath.types.FhirPathDate
import com.google.fhir.fhirpath.types.FhirPathDateTime
import com.google.fhir.fhirpath.types.FhirPathQuantity
import com.google.fhir.fhirpath.types.FhirPathTime
import com.google.fhir.model.r4.CodeableConcept
import com.google.fhir.model.r4.Enumeration
import com.google.fhir.model.r4.Extension
import com.google.fhir.model.r4.FhirDate
import com.google.fhir.model.r4.FhirDateTime
import com.google.fhir.model.r4.FhirR4Json
import com.google.fhir.model.r4.OperationOutcome
import com.google.fhir.model.r4.Parameters
import com.google.fhir.model.r4.Resource
import com.google.fhir.model.r4.Time
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.google.fhir.model.r4.String as FhirString
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.encoding.Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

class R4FhirpathRequestHandler: RequestHandler {
    private val parser = FhirR4Json()
    private val JSON = Json {}
    private val engine = FhirPathEngine.forR4()

    override suspend fun handle(call: ApplicationCall) {
        val content = call.receiveText()
        val inputParameters = try {
            parser.decodeFromString(content) as Parameters
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, OperationOutcome.IssueType.Exception, e.message)
            return
        }

        val expressionStr = inputParameters.parameter.find { it.name.value == "expression" }?.value?.asString()?.value?.value
        if (expressionStr.isNullOrBlank()) {
            call.respondError(HttpStatusCode.BadRequest, OperationOutcome.IssueType.Incomplete, "Cannot evaluate without a fhirpath expression")
            return
        }

        val contextStr = inputParameters.parameter.find { it.name.value == "context" }?.value?.asString()?.value?.value
        val inputParametersVariablesPart = inputParameters.parameter.find { it.name.value == "variables" }?.part
        val parameterVariables = inputParametersVariablesPart?.mapNotNull { parameter ->
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

        val resource = inputParameters.parameter.find { it.name.value == "resource" }?.resource
        if (resource == null) {
            call.respondError(HttpStatusCode.BadRequest, OperationOutcome.IssueType.Invalid, "Cannot evaluate without a resource")
            return
        }

        val terminologyServer = inputParameters.parameter.find { it.name.value == "terminologyserver" }?.value?.asString()?.value?.value

        val variables = buildMap {
            if (parameterVariables != null) {
                putAll(parameterVariables)
            }
        }

        val resultParameters = try {
            buildResultParameters(expressionStr, resource, contextStr, variables, terminologyServer)
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, OperationOutcome.IssueType.Exception, e.message)
            e.printStackTrace()
            return
        }
        val echoParameters = Parameters.Parameter(
            name = FhirString(value = "parameters"),
            part = buildList {
                add(Parameters.Parameter(
                    name = FhirString(value = "evaluator"),
                    value = Parameters.Parameter.Value.String(
                        value = FhirString(value = "Kotlin FHIRPath 1.0.0-beta01 (R4)")
                    ),
                ))
                contextStr?.run {
                    add(Parameters.Parameter(
                        name = FhirString(value = "context"),
                        value = Parameters.Parameter.Value.String(value = FhirString(value = contextStr)),
                    ))
                }
                add(Parameters.Parameter(
                    name = FhirString(value = "expression"),
                    value = Parameters.Parameter.Value.String(value = FhirString(value = expressionStr)),
                ))
                add(Parameters.Parameter(
                    name = FhirString(value = "resource"),
                    resource = resource,
                ))
                inputParametersVariablesPart?.run {
                    add(
                        Parameters.Parameter(
                            name = FhirString(value = "variables"),
                            part = inputParametersVariablesPart.map { it },
                        )
                    )
                }
            },
        )

        val responseParameters = Parameters(
            id = "fhirpath",
            parameter = listOf(echoParameters) + resultParameters,
        )

        call.respondText(parser.encodeToString(responseParameters), ContentType.Application.Json)
    }


    private suspend fun buildResultParameters(
        expression: String,
        resource: Resource,
        contextExpression: String?,
        variables: Map<String, Any>,
        terminologyServer: String?
    ): List<Parameters.Parameter> = withContext(Dispatchers.Default) {
        val resourceTypeName = resource::class.simpleName!!
        val resultParts = if (contextExpression != null) {
            val contextResults = engine.evaluateExpression(contextExpression, resource, variables)
            contextResults.mapIndexed { index, contextResult ->
                val contextLabel = "$resourceTypeName.$contextExpression[$index]"
                val expressionResult = engine.evaluateExpression(expression, contextResult, variables)

                Parameters.Parameter(
                    name = FhirString(value = "result"),
                    value = Parameters.Parameter.Value.String(value = FhirString(value = contextLabel)),
                    part = engineResultsToParameters(expressionResult),
                )
            }
        } else {
            val  expressionResult = engine.evaluateExpression(expression, resource, variables)
            listOf(
                Parameters.Parameter(
                    name = FhirString(value = "result"),
                    part = engineResultsToParameters(expressionResult),
                )
            )
        }

        resultParts
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        issueType: OperationOutcome.IssueType,
        message: String?,
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
            Json,
            status,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun engineResultsToParameters(results: Collection<Any>): List<Parameters.Parameter> {
        return results.map {
            when(it) {
                is BigDecimal -> Parameters.Parameter(name = FhirString(value = "decimal"), value = Parameters.Parameter.Value.Decimal(value = com.google.fhir.model.r4.Decimal(value = it)))
                is FhirPathQuantity -> Parameters.Parameter(name = FhirString(value = "quantity"), value = Parameters.Parameter.Value.Quantity(value = com.google.fhir.model.r4.Quantity(value = com.google.fhir.model.r4.Decimal(value = it.value), unit = FhirString(value = it.unit),)),)
                is FhirPathDateTime -> Parameters.Parameter(name = FhirString(value = "dateTime"), value = Parameters.Parameter.Value.DateTime(value = com.google.fhir.model.r4.DateTime(value = FhirDateTime.fromString(it.toString()))))
                is FhirPathDate -> Parameters.Parameter(name = FhirString(value = "date"), value = Parameters.Parameter.Value.Date(value = com.google.fhir.model.r4.Date(value = FhirDate.fromString(it.toString()))))
                is FhirPathTime -> Parameters.Parameter(name = FhirString(value = "time"), value = Parameters.Parameter.Value.Time(value = Time(value = it.toLocalTime())))
                is Int -> Parameters.Parameter(name = FhirString(value = "integer"),value = Parameters.Parameter.Value.Integer(value = com.google.fhir.model.r4.Integer(value = it)),)
                is Boolean -> Parameters.Parameter(name = FhirString(value = "boolean"), value = Parameters.Parameter.Value.Boolean(value = com.google.fhir.model.r4.Boolean(value = it)))
                is String -> Parameters.Parameter(name = FhirString(value = "string"), value = Parameters.Parameter.Value.String(value = FhirString(value = it)))

                is com.google.fhir.model.r4.Base64Binary -> Parameters.Parameter(name = FhirString(value = "base64Binary"), value = Parameters.Parameter.Value.Base64Binary(it))
                is com.google.fhir.model.r4.Canonical -> Parameters.Parameter(name = FhirString(value = "canonical"), value = Parameters.Parameter.Value.Canonical(it))
                is com.google.fhir.model.r4.Code -> Parameters.Parameter(name = FhirString(value = "code"), value = Parameters.Parameter.Value.Code(it))
                is com.google.fhir.model.r4.Date -> Parameters.Parameter(name = FhirString(value = "date"), value = Parameters.Parameter.Value.Date(it))
                is com.google.fhir.model.r4.Id -> Parameters.Parameter(name = FhirString(value = "id"), value = Parameters.Parameter.Value.Id(it))
                is com.google.fhir.model.r4.Instant -> Parameters.Parameter(name = FhirString(value = "instant"), value = Parameters.Parameter.Value.Instant(it))
                is com.google.fhir.model.r4.Markdown -> Parameters.Parameter(name = FhirString(value = "markdown"), value = Parameters.Parameter.Value.Markdown(it))
                is com.google.fhir.model.r4.Oid -> Parameters.Parameter(name = FhirString(value = "oid"), value = Parameters.Parameter.Value.Oid(it))
                is com.google.fhir.model.r4.PositiveInt -> Parameters.Parameter(name = FhirString(value = "positiveInt"), value = Parameters.Parameter.Value.PositiveInt(it))
                is com.google.fhir.model.r4.UnsignedInt -> Parameters.Parameter(name = FhirString(value = "unsignedInt"), value = Parameters.Parameter.Value.UnsignedInt(it))
                is com.google.fhir.model.r4.Uuid -> Parameters.Parameter(name = FhirString(value = "uuid"), value = Parameters.Parameter.Value.Uuid(it))
                is com.google.fhir.model.r4.Url -> Parameters.Parameter(name = FhirString(value = "url"), value = Parameters.Parameter.Value.Url(it))
                is com.google.fhir.model.r4.Uri -> Parameters.Parameter(name = FhirString(value = "uri"), value = Parameters.Parameter.Value.Uri(it))
                is com.google.fhir.model.r4.Address -> Parameters.Parameter(name = FhirString(value = "address"), value = Parameters.Parameter.Value.Address(it))
                is com.google.fhir.model.r4.Age -> Parameters.Parameter(name = FhirString(value = "age"), value = Parameters.Parameter.Value.Age(it))
                is com.google.fhir.model.r4.Annotation -> Parameters.Parameter(name = FhirString(value = "annotation"), value = Parameters.Parameter.Value.Annotation(it))
                is com.google.fhir.model.r4.Attachment -> Parameters.Parameter(name = FhirString(value = "attachment"), value = Parameters.Parameter.Value.Attachment(it))
                is com.google.fhir.model.r4.CodeableConcept -> Parameters.Parameter(name = FhirString(value = "codeableConcept"), value = Parameters.Parameter.Value.CodeableConcept(it))
                is com.google.fhir.model.r4.Coding -> Parameters.Parameter(name = FhirString(value = "coding"), value = Parameters.Parameter.Value.Coding(it))
                is com.google.fhir.model.r4.ContactPoint -> Parameters.Parameter(name = FhirString(value = "contactPoint"), value = Parameters.Parameter.Value.ContactPoint(it))
                is com.google.fhir.model.r4.Count -> Parameters.Parameter(name = FhirString(value = "count"), value = Parameters.Parameter.Value.Count(it))
                is com.google.fhir.model.r4.Distance -> Parameters.Parameter(name = FhirString(value = "distance"), value = Parameters.Parameter.Value.Distance(it))
                is com.google.fhir.model.r4.Duration -> Parameters.Parameter(name = FhirString(value = "duration"), value = Parameters.Parameter.Value.Duration(it))
                is com.google.fhir.model.r4.HumanName -> Parameters.Parameter(name = FhirString(value = "humanName"), value = Parameters.Parameter.Value.HumanName(it))
                is com.google.fhir.model.r4.Identifier -> Parameters.Parameter(name = FhirString(value = "identifier"), value = Parameters.Parameter.Value.Identifier(it))
                is com.google.fhir.model.r4.Money -> Parameters.Parameter(name = FhirString(value = "money"), value = Parameters.Parameter.Value.Money(it))
                is com.google.fhir.model.r4.Period -> Parameters.Parameter(name = FhirString(value = "period"), value = Parameters.Parameter.Value.Period(it))
                is com.google.fhir.model.r4.Quantity -> Parameters.Parameter(name = FhirString(value = "quantity"), value = Parameters.Parameter.Value.Quantity(it))
                is com.google.fhir.model.r4.Range -> Parameters.Parameter(name = FhirString(value = "range"), value = Parameters.Parameter.Value.Range(it))
                is com.google.fhir.model.r4.Ratio -> Parameters.Parameter(name = FhirString(value = "ratio"), value = Parameters.Parameter.Value.Ratio(it))
                is com.google.fhir.model.r4.Reference -> Parameters.Parameter(name = FhirString(value = "reference"), value = Parameters.Parameter.Value.Reference(it))
                is com.google.fhir.model.r4.SampledData -> Parameters.Parameter(name = FhirString(value = "sampledData"), value = Parameters.Parameter.Value.SampledData(it))
                is com.google.fhir.model.r4.Signature -> Parameters.Parameter(name = FhirString(value = "signature"), value = Parameters.Parameter.Value.Signature(it))
                is com.google.fhir.model.r4.Timing -> Parameters.Parameter(name = FhirString(value = "timing"), value = Parameters.Parameter.Value.Timing(it))
                is com.google.fhir.model.r4.ContactDetail -> Parameters.Parameter(name = FhirString(value = "contactDetail"), value = Parameters.Parameter.Value.ContactDetail(it))
                is com.google.fhir.model.r4.Contributor -> Parameters.Parameter(name = FhirString(value = "contributor"), value = Parameters.Parameter.Value.Contributor(it))
                is com.google.fhir.model.r4.DataRequirement -> Parameters.Parameter(name = FhirString(value = "dataRequirement"), value = Parameters.Parameter.Value.DataRequirement(it))
                is com.google.fhir.model.r4.Expression -> Parameters.Parameter(name = FhirString(value = "expression"), value = Parameters.Parameter.Value.Expression(it))
                is com.google.fhir.model.r4.ParameterDefinition -> Parameters.Parameter(name = FhirString(value = "parameterDefinition"), value = Parameters.Parameter.Value.ParameterDefinition(it))
                is com.google.fhir.model.r4.RelatedArtifact -> Parameters.Parameter(name = FhirString(value = "relatedArtifact"), value = Parameters.Parameter.Value.RelatedArtifact(it))
                is com.google.fhir.model.r4.TriggerDefinition -> Parameters.Parameter(name = FhirString(value = "triggerDefinition"), value = Parameters.Parameter.Value.TriggerDefinition(it))
                is com.google.fhir.model.r4.UsageContext -> Parameters.Parameter(name = FhirString(value = "usageContext"), value = Parameters.Parameter.Value.UsageContext(it))
                is com.google.fhir.model.r4.Dosage -> Parameters.Parameter(name = FhirString(value = "dosage"), value = Parameters.Parameter.Value.Dosage(it))
                is com.google.fhir.model.r4.Meta -> Parameters.Parameter(name = FhirString(value = "meta"), value = Parameters.Parameter.Value.Meta(it))
//                is Resource -> Parameters.Parameter(
//                    name = FhirString(value = it::class.simpleName?.lowercase() ?: "resource"),
//                    resource = it,
//                )
                else -> Parameters.Parameter(
                    extension = listOf(
                        Extension(url = "http://fhir.forms-lab.com/StructureDefinition/json-value",
                            value = Extension.Value.String(value = FhirString(value = JSON.encodeToString(
                                DynamicLookupSerializer(), it))))
                    ),
                    name = FhirString(value = it::class.simpleName?.lowercase()),
                )
            }
        }
    }

    private fun FhirPathTime.toLocalTime(): LocalTime = LocalTime(
        hour   = this.hour,
        minute = this.minute ?: 0,
        second = this.second?.toInt() ?: 0,
        nanosecond = this.second?.let { s ->
            ((s - s.toInt()) * 1_000_000_000).toInt()
        } ?: 0,
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

@ExperimentalSerializationApi
class DynamicLookupSerializer: KSerializer<Any> {
    override val descriptor: SerialDescriptor = ContextualSerializer(Any::class, null, emptyArray()).descriptor

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Any) {
        val actualSerializer = encoder.serializersModule.getContextual(value::class) ?: value::class.serializer()
        encoder.encodeSerializableValue(actualSerializer as KSerializer<Any>, value)
    }

    override fun deserialize(decoder: Decoder): Any {
        error("Unsupported")
    }
}
