/**
 * Errors thrown by `AuthContext.getAccessToken`.
 *
 * Callers (the API client's 401 interceptor in particular) use the class to
 * decide between "this request failed, try again later" and "the session is
 * over, go to login". Only `SessionExpiredError` may trigger a login redirect.
 */

/** The session is over: no tokens, or the refresh token was rejected by the server. */
export class SessionExpiredError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SessionExpiredError'
  }
}

/**
 * The access token could not be refreshed right now for a transient reason
 * (network down, auth server rolling, cooldown). The session is intact and a
 * retry is scheduled — fail this one request, do NOT log the user out.
 */
export class TokenUnavailableError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TokenUnavailableError'
  }
}
