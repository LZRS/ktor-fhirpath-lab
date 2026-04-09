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
    private val parser = FhirR5Json()
    override suspend fun handle(call: ApplicationCall) {
        TODO("to be implemented")
    }
}