package io.kelta.worker.module;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a failed module load is the module's fault or the infrastructure's.
 *
 * <p>The distinction is what separates {@code QUARANTINED} — a terminal status a human has to clear
 * — from "fail closed on this pod and try again". On 2026-10-06 the object store refused
 * connections while worker pods were loading modules; every failure was treated as the module's
 * fault, every module on every tenant was persisted {@code QUARANTINED}, and startup only loads
 * {@code ACTIVE} rows, so they stayed down until someone flipped the rows back by hand. Nothing about
 * the modules was wrong.
 *
 * <p>Classification is by <b>phase</b> first, because the phase says what was being trusted at the
 * time far more reliably than an exception type does:
 * <ul>
 *   <li>{@link Phase#SOURCE} — where the implementation comes from. Classified by the caller, not
 *       here: a manifest-only install has no JAR at all (permanent), while a pod without JAR
 *       storage is a property of that pod (transient).</li>
 *   <li>{@link Phase#FETCH} — downloading the JAR from object storage. Transient, except when the
 *       object is definitively absent ({@link NoSuchKeyException}): the stored artifact is gone and
 *       no amount of retrying brings it back.</li>
 *   <li>{@link Phase#VERIFY} — checksum and signature re-verification. Permanent: a mismatch is the
 *       tamper signal this phase exists to catch. Only an I/O failure reading the cached file, or a
 *       database outage looking up the tenant's signing keys, is transient.</li>
 *   <li>{@link Phase#LOAD} — classloading, instantiation, {@code onStartup}, and registering
 *       handlers, hooks, services and routes. Permanent (class not found, linkage error, a module
 *       that throws, a rejected service port) unless the cause chain is a platform connectivity
 *       failure — a database or network outage while {@code onStartup} ran says nothing about the
 *       module.</li>
 * </ul>
 *
 * <p>A {@link ModuleSignatureException} is permanent in every phase.
 */
public final class ModuleLoadFailureClassifier {

    /** Bounds the cause-chain walk; a cyclic or absurdly deep chain is not worth following. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Where in the load a failure happened. */
    public enum Phase {
        SOURCE, FETCH, VERIFY, LOAD;

        /** Lower-case form used as a metric tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether trying the same load again can succeed without anyone changing anything. */
    public enum Kind {
        /** Infrastructure: fail closed on this pod, keep the persisted status, retry. */
        TRANSIENT,
        /** The module itself: quarantine, persisted, until an admin re-enables it. */
        PERMANENT;

        /** Lower-case form used as a metric tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private ModuleLoadFailureClassifier() {
    }

    /**
     * Classifies a failure that happened during {@code phase}.
     *
     * @param phase the load phase that was running when {@code error} was thrown
     * @param error the failure
     * @return whether retrying can help
     */
    public static Kind classify(Phase phase, Throwable error) {
        if (causeChainContains(error, ModuleSignatureException.class)) {
            return Kind.PERMANENT;
        }
        return switch (phase) {
            case SOURCE -> Kind.TRANSIENT;
            case FETCH -> causeChainContains(error, NoSuchKeyException.class)
                    ? Kind.PERMANENT : Kind.TRANSIENT;
            case VERIFY -> causeChainContains(error, IOException.class)
                    || isInfrastructureUnavailable(error) ? Kind.TRANSIENT : Kind.PERMANENT;
            case LOAD -> isInfrastructureUnavailable(error) ? Kind.TRANSIENT : Kind.PERMANENT;
        };
    }

    /**
     * Whether the cause chain shows a platform dependency (database, network, object storage) was
     * unreachable, as opposed to something being wrong with what it returned.
     *
     * <p>Deliberately narrow. A broad {@code IOException} or {@code DataAccessException} would also
     * match a module bug, which must still quarantine.
     */
    static boolean isInfrastructureUnavailable(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof SQLTransientException
                    || current instanceof SQLRecoverableException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof SdkClientException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean causeChainContains(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
