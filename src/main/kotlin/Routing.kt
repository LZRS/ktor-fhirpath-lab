package dev.ohs

import io.ktor.server.application.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Clock

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
            post("/\$fhirpath") {
                val fhirpathR4Handler = dependencies.resolve<RequestHandler>("FhirpathR4Handler")
                fhirpathR4Handler.handle(call)
            }
            post("/\$fhirpath-r5") {
                val fhirpathR5Handler = dependencies.resolve<RequestHandler>("FhirpathR5Handler")
                fhirpathR5Handler.handle(call)
            }
        }
    }
}
