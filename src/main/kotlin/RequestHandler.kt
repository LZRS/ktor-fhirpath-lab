package dev.ohs

import io.ktor.server.application.ApplicationCall

fun interface RequestHandler {
    suspend fun handle(call: ApplicationCall)
}