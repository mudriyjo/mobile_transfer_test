package com.bank.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

val BackendJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

data class BankBackendEnvironment(
    val directory: BankDirectory = BankDirectory(),
    val ledger: TransferLedger = TransferLedger(),
    val faults: FaultController = FaultController(),
    val controlsEnabled: Boolean = true,
)

fun Application.bankBackendModule(
    environment: BankBackendEnvironment = BankBackendEnvironment(),
) {
    install(ContentNegotiation) {
        json(BackendJson)
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = "INTERNAL_ERROR",
                    message = "The backend stub could not process the request",
                    retriable = true,
                ),
            )
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        route("/v1") {
            get("/accounts") {
                call.respond(environment.directory.accounts())
            }
            get("/beneficiaries") {
                call.respond(environment.directory.beneficiaries())
            }
            post("/beneficiaries") {
                val request = try {
                    call.receive<CreateBeneficiaryRequest>().normalized()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = "INVALID_REQUEST",
                            message = "The beneficiary payload is invalid",
                        ),
                    )
                    return@post
                }

                request.validationError()?.let { message ->
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorResponse(code = "VALIDATION_ERROR", message = message),
                    )
                    return@post
                }

                when (val result = environment.directory.createBeneficiary(request)) {
                    is CreateBeneficiaryResult.Created ->
                        call.respond(HttpStatusCode.Created, result.beneficiary)
                    CreateBeneficiaryResult.Conflict -> call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(
                            code = "BENEFICIARY_ALREADY_EXISTS",
                            message = "A beneficiary already uses this account identifier",
                        ),
                    )
                }
            }
            transferRoutes(environment)
        }

        if (environment.controlsEnabled) {
            controlRoutes(environment)
        }
    }
}

private fun Route.transferRoutes(environment: BankBackendEnvironment) {
    post("/transfers") {
        val operationId = call.request.header("Idempotency-Key")?.trim()
        if (operationId == null || !operationId.isValidOperationId()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = "INVALID_IDEMPOTENCY_KEY",
                    message = "Idempotency-Key must contain 8 to 128 safe characters",
                ),
            )
            return@post
        }

        val request = try {
            call.receive<CreateTransferRequest>()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(code = "INVALID_REQUEST", message = "The transfer payload is invalid"),
            )
            return@post
        }

        request.validationError(environment.directory)?.let { message ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(code = "VALIDATION_ERROR", message = message),
            )
            return@post
        }

        val alreadyCommitted = environment.ledger.findByOperationId(operationId) != null
        val submitMode = if (alreadyCommitted) {
            SubmitFaultMode.NORMAL
        } else {
            environment.faults.consumeSubmitMode()
        }

        if (submitMode == SubmitFaultMode.REJECT_BEFORE_COMMIT) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    code = "REJECTED_BEFORE_COMMIT",
                    message = "The synthetic backend rejected the request before commit",
                    retriable = true,
                    outcome = OperationOutcome.NOT_COMMITTED,
                ),
            )
            return@post
        }

        when (val result = environment.ledger.submit(operationId, request)) {
            is SubmitResult.Conflict -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        code = "IDEMPOTENCY_CONFLICT",
                        message = "The idempotency key is already bound to a different payload",
                    ),
                )
            }

            is SubmitResult.Replay -> {
                call.response.header("Idempotent-Replay", "true")
                call.respond(HttpStatusCode.OK, result.transfer)
            }

            is SubmitResult.Committed -> {
                val transfer = environment.faults.terminalStatusImmediatelyAfterCommit()
                    ?.let { environment.ledger.updateStatus(operationId, it) }
                    ?: result.transfer

                when (submitMode) {
                    SubmitFaultMode.NORMAL -> call.respond(HttpStatusCode.Accepted, transfer)
                    SubmitFaultMode.COMMIT_THEN_TIMEOUT -> {
                        delay(environment.faults.submitDelayMillis())
                        call.respond(
                            HttpStatusCode.GatewayTimeout,
                            ErrorResponse(
                                code = "RESPONSE_LOST_AFTER_COMMIT",
                                message = "The response was lost after the transfer was committed",
                                retriable = true,
                                outcome = OperationOutcome.UNKNOWN_TO_CLIENT,
                            ),
                        )
                    }

                    SubmitFaultMode.COMMIT_THEN_MALFORMED_RESPONSE -> call.respondText(
                        text = "{\"transferId\":",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )

                    SubmitFaultMode.BLOCK_AFTER_COMMIT -> {
                        environment.faults.awaitSubmissionRelease()
                        call.respond(HttpStatusCode.Accepted, transfer)
                    }

                    SubmitFaultMode.REJECT_BEFORE_COMMIT -> error("Handled before ledger commit")
                }
            }
        }
    }

    get("/transfers/by-operation/{operationId}") {
        val operationId = call.parameters["operationId"]
        val current = operationId?.let(environment.ledger::findByOperationId)
        if (operationId == null || current == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(code = "TRANSFER_NOT_FOUND", message = "No transfer exists for this operation ID"),
            )
            return@get
        }

        when (val decision = environment.faults.nextStatusDecision(operationId, current.status)) {
            StatusDecision.TemporaryFailure -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    code = "STATUS_TEMPORARILY_UNAVAILABLE",
                    message = "Transfer status is temporarily unavailable",
                    retriable = true,
                ),
            )

            StatusDecision.ReturnCurrent -> call.respond(current)
            is StatusDecision.ChangeStatus -> call.respond(
                environment.ledger.updateStatus(operationId, decision.status) ?: current,
            )
        }
    }

    get("/transfers/{transferId}") {
        val transfer = call.parameters["transferId"]?.let(environment.ledger::findByTransferId)
        if (transfer == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(code = "TRANSFER_NOT_FOUND", message = "No transfer exists for this ID"),
            )
        } else {
            call.respond(transfer)
        }
    }
}

private fun Route.controlRoutes(environment: BankBackendEnvironment) {
    route("/__control") {
        get("/faults") {
            call.respond(environment.faults.snapshot())
        }

        put("/faults") {
            val requestedPlan = try {
                call.receive<FaultPlan>()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "INVALID_FAULT_PLAN", message = "The fault plan payload is invalid"),
                )
                return@put
            }
            val state = try {
                environment.faults.configure(requestedPlan)
            } catch (error: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "INVALID_FAULT_PLAN", message = error.message ?: "Invalid fault plan"),
                )
                return@put
            }
            call.respond(state)
        }

        get("/journal") {
            call.respond(environment.ledger.journal())
        }

        post("/release-blocked") {
            call.respond(ReleaseResponse(environment.faults.releaseBlockedSubmissions()))
        }

        post("/reset") {
            environment.ledger.clear()
            environment.faults.reset()
            call.respond(ResetResponse(reset = true))
        }
    }
}

private fun String.isValidOperationId(): Boolean =
    length in 8..128 && all { it.isLetterOrDigit() || it in "-_.:" }
