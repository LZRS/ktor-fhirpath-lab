package dev.ohs

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

fun Application.configureFrameworks() {
    dependencies {
        provide<RequestHandler>("FhirpathR4Handler") {
            R4FhirpathRequestHandler()
        }
        provide<RequestHandler>("FhirpathR5Handler") {
            R5RequestHandler()
        }
    }
}
