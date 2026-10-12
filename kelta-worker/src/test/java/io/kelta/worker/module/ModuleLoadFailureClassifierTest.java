package io.kelta.worker.module;

import io.kelta.worker.module.ModuleLoadFailureClassifier.Kind;
import io.kelta.worker.module.ModuleLoadFailureClassifier.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModuleLoadFailureClassifier")
class ModuleLoadFailureClassifierTest {

    @Nested
    @DisplayName("fetch phase — downloading the JAR from object storage")
    class Fetch {

        @Test
        @DisplayName("connection refused is transient (the 2026-10-06 incident)")
        void connectionRefusedIsTransient() {
            SdkClientException refused = SdkClientException.builder()
                    .message("Unable to execute HTTP request: Connection refused")
                    .cause(new ConnectException("Connection refused"))
                    .build();
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH, refused))
                    .isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("a timeout, a 5xx and a local cache write failure are transient")
        void otherStorageFailuresAreTransient() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH,
                    new SocketTimeoutException("Read timed out"))).isEqualTo(Kind.TRANSIENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH,
                    S3Exception.builder().statusCode(503).message("Slow Down").build()))
                    .isEqualTo(Kind.TRANSIENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH,
                    new IOException("No space left on device"))).isEqualTo(Kind.TRANSIENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH,
                    new IllegalStateException("S3 storage is not enabled")))
                    .isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("the object being gone is permanent — retrying cannot bring it back")
        void missingObjectIsPermanent() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.FETCH,
                    NoSuchKeyException.builder().message("no such key").build()))
                    .isEqualTo(Kind.PERMANENT);
        }
    }

    @Nested
    @DisplayName("verify phase — checksum and signature")
    class Verify {

        @Test
        @DisplayName("a checksum or signature failure is permanent")
        void signatureFailureIsPermanent() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new ModuleSignatureException("checksum mismatch"))).isEqualTo(Kind.PERMANENT);
        }

        @Test
        @DisplayName("a signature failure is permanent even if its cause looks like infrastructure")
        void signatureFailureWinsOverCause() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new ModuleSignatureException("bad", new ConnectException("refused"))))
                    .isEqualTo(Kind.PERMANENT);
        }

        @Test
        @DisplayName("the database being down while reading signing keys is transient")
        void databaseDownIsTransient() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new CannotGetJdbcConnectionException("Connection refused")))
                    .isEqualTo(Kind.TRANSIENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new QueryTimeoutException("statement timeout"))).isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("failing to read the cached JAR is transient")
        void cachedFileUnreadableIsTransient() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new NoSuchFileException("/tmp/kelta-module-cache/x.jar")))
                    .isEqualTo(Kind.TRANSIENT);
        }

        @Test
        @DisplayName("anything else is permanent")
        void otherFailuresArePermanent() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.VERIFY,
                    new IllegalArgumentException("bad key"))).isEqualTo(Kind.PERMANENT);
        }
    }

    @Nested
    @DisplayName("load phase — classloading and onStartup")
    class Load {

        @Test
        @DisplayName("class not found, linkage errors and a throwing module are permanent")
        void moduleFaultsArePermanent() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new ClassNotFoundException("com.example.Missing"))).isEqualTo(Kind.PERMANENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new NoClassDefFoundError("com/example/Dep"))).isEqualTo(Kind.PERMANENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new UnsupportedClassVersionError("class file version 70"))).isEqualTo(Kind.PERMANENT);
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new IllegalStateException("tried to publish an undeclared port")))
                    .isEqualTo(Kind.PERMANENT);
        }

        @Test
        @DisplayName("a data error from onStartup is the module's problem, not infrastructure")
        void dataErrorsArePermanent() {
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new DataIntegrityViolationException("duplicate key"))).isEqualTo(Kind.PERMANENT);
            // A plain IOException in the load phase is not singled out: only the fetch and verify
            // phases treat local I/O as infrastructure.
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD,
                    new IOException("module resource missing"))).isEqualTo(Kind.PERMANENT);
        }

        @Test
        @DisplayName("onStartup failing because the database is unreachable is transient")
        void connectivityFromOnStartupIsTransient() {
            RuntimeException wrapped = new RuntimeException("onStartup failed",
                    new SQLTransientConnectionException("HikariPool - Connection is not available"));
            assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD, wrapped))
                    .isEqualTo(Kind.TRANSIENT);
        }
    }

    @Test
    @DisplayName("the source phase (no JAR storage on this pod) is transient")
    void sourceIsTransient() {
        assertThat(ModuleLoadFailureClassifier.classify(Phase.SOURCE,
                new IllegalStateException("no storage"))).isEqualTo(Kind.TRANSIENT);
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates")
    void selfReferencingCauseTerminates() {
        Exception looping = new Exception("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(ModuleLoadFailureClassifier.classify(Phase.LOAD, looping))
                .isEqualTo(Kind.PERMANENT);
    }
}
