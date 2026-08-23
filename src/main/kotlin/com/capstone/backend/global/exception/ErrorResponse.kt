package com.capstone.backend.global.exception

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String,
    val path: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val fieldErrors: List<FieldError>? = null,
) {
    data class FieldError(
        val field: String,
        val message: String,
    )

    companion object {
        fun of(
            errorCode: ErrorCode,
            path: String? = null,
            message: String = errorCode.message,
        ): ErrorResponse =
            ErrorResponse(
                code = errorCode.name,
                message = message,
                path = path,
            )

        fun of(
            errorCode: ErrorCode,
            path: String?,
            fieldErrors: List<FieldError>,
        ): ErrorResponse =
            ErrorResponse(
                code = errorCode.name,
                message = errorCode.message,
                path = path,
                fieldErrors = fieldErrors,
            )
    }
}
