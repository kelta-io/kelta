package io.kelta.modules.billing;

/**
 * A non-2xx response from Stripe, carrying the processor's own error type and code.
 *
 * <p>The message is for logs. Never return it to a caller: it can name account internals.
 */
public class StripeApiException extends RuntimeException {

    private final int status;
    private final String errorType;
    private final String errorCode;

    public StripeApiException(int status, String errorType, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorType = errorType;
        this.errorCode = errorCode;
    }

    public int getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** A bad or revoked key — an operator problem, not the member's. */
    public boolean isAuthFailure() {
        return status == 401 || "authentication_error".equals(errorType);
    }
}
