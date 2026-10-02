package edu.sustech.mobile.core

/**
 * Every service failure the app can raise.
 *
 * The flags exist so the UI never has to guess at a cause:
 *  - [offCampus]: the service refused the request from outside the campus
 *    network (PMS answers 403 "Access forbidden"). PMS is campus-only, so this
 *    says nothing about the account.
 *  - [signInRequired]: the session is gone or was never established; the caller
 *    re-authenticates from the stored account.
 *  - [refused]: the service *answered* and rejected the credentials. Only this
 *    one justifies telling the user their account is wrong — a network failure,
 *    an off-campus reply or a 5xx must never be reported as bad credentials.
 *  - [httpStatus]: the actual response code when the server supplied one.
 */
open class ApiException(
    message: String,
    val offCampus: Boolean = false,
    val signInRequired: Boolean = false,
    val refused: Boolean = false,
    val httpStatus: Int? = null,
) : Exception(message)
