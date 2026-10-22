package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.interceptor.DerivedDataController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The email log names recipients and subjects of mail sent about a record, so it is derived data:
 * the caller must be able to read that record, which must exist in the named collection — a
 * record id from another collection, or one the caller cannot read, is answered like a missing
 * one.
 */
@DisplayName("EmailLogController")
class EmailLogControllerTest {

    private static final Map<String, Object> RECORD = Map.of("id", "rec-1", "name", "Acme");

    private EmailRepository emailRepository;
    private CollectionRegistry collectionRegistry;
    private QueryEngine queryEngine;
    private CerbosRecordAuthorizationAdvice recordAuthorization;
    private CerbosPermissionResolver permissionResolver;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private CollectionDefinition accounts;
    private EmailLogController controller;

    @BeforeEach
    void setUp() {
        emailRepository = mock(EmailRepository.class);
        collectionRegistry = mock(CollectionRegistry.class);
        queryEngine = mock(QueryEngine.class);
        recordAuthorization = mock(CerbosRecordAuthorizationAdvice.class);
        permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        accounts = mock(CollectionDefinition.class);
        controller = new EmailLogController(emailRepository, collectionRegistry, queryEngine,
                recordAuthorization, permissionResolver, bootstrapRepository);

        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        grant(true);
        when(collectionRegistry.get("accounts")).thenReturn(accounts);
        when(queryEngine.getById(accounts, "rec-1")).thenReturn(Optional.of(RECORD));
        when(recordAuthorization.isRecordReadable(request, "accounts", RECORD)).thenReturn(true);
        when(emailRepository.findLogsBySourceId("rec-1", 20)).thenReturn(List.of(Map.of(
                "id", "log-1", "recipient_email", "a@example.com", "subject", "Hello", "status", "SENT")));
    }

    private void grant(boolean granted) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_EMAIL_TEMPLATES", "granted", granted)));
    }

    @Test
    @DisplayName("is derived data: the record advice leaves its body alone")
    void isDerivedData() {
        assertThat(controller).isInstanceOf(DerivedDataController.class);
    }

    @Test
    @DisplayName("lists the log of a record the caller can read")
    @SuppressWarnings("unchecked")
    void listsReadableRecord() {
        var response = controller.listLogsByRecord("accounts", "rec-1", 20, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("type")).isEqualTo("email-logs");
    }

    @Test
    @DisplayName("needs MANAGE_EMAIL_TEMPLATES before it reads anything")
    void needsPermission() {
        grant(false);

        assertThatThrownBy(() -> controller.listLogsByRecord("accounts", "rec-1", 20, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(queryEngine, emailRepository);
    }

    @Test
    @DisplayName("a record the caller cannot read is a 404, and its log is never queried")
    void unreadableRecord() {
        when(recordAuthorization.isRecordReadable(request, "accounts", RECORD)).thenReturn(false);

        var response = controller.listLogsByRecord("accounts", "rec-1", 20, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(emailRepository, never()).findLogsBySourceId(anyString(), anyInt());
    }

    @Test
    @DisplayName("a record id that is not in the named collection is a 404 — naming a readable collection proves nothing")
    void recordInAnotherCollection() {
        when(queryEngine.getById(accounts, "rec-of-other-collection")).thenReturn(Optional.empty());

        var response = controller.listLogsByRecord("accounts", "rec-of-other-collection", 20, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(recordAuthorization, never()).isRecordReadable(any(), any(), any());
        verify(emailRepository, never()).findLogsBySourceId(anyString(), anyInt());
    }

    @Test
    @DisplayName("an unknown collection is a 404")
    void unknownCollection() {
        var response = controller.listLogsByRecord("nope", "rec-1", 20, request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(queryEngine, emailRepository);
    }

    @Test
    @DisplayName("clamps the page size to 1..100")
    void clampsLimit() {
        when(emailRepository.findLogsBySourceId(eq("rec-1"), anyInt())).thenReturn(List.of());

        controller.listLogsByRecord("accounts", "rec-1", 5000, request);

        verify(emailRepository).findLogsBySourceId("rec-1", 100);
    }
}
