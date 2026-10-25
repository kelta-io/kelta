package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.CompositeUniqueConstraintService;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompositeUniqueConstraintController Tests")
class CompositeUniqueConstraintControllerTest {

    private final MockHttpServletRequest adminRequest = AdminPermissionGuardFixture.request();

    @Mock private CollectionRegistry collectionRegistry;
    @Mock private CompositeUniqueConstraintService constraintService;
    private CompositeUniqueConstraintController controller;
    private CollectionDefinition availability;

    @BeforeEach
    void setUp() {
        controller = new CompositeUniqueConstraintController(collectionRegistry, constraintService,
                AdminPermissionGuardFixture.granting("CUSTOMIZE_APPLICATION"));
        availability = new CollectionDefinition(
                "availability",
                "Availability",
                "test",
                List.of(new FieldDefinition("title", FieldType.STRING, false, false, false, null, null, null, null, null)),
                new StorageConfig("availability", Map.of()),
                null, null,
                1L, Instant.now(), Instant.now());
    }

    @Test
    void createReturns404WhenCollectionUnknown() {
        when(collectionRegistry.get("nope")).thenReturn(null);

        ResponseEntity<?> response = controller.create(adminRequest, "nope", Map.of("fieldNames", List.of("a", "b")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(constraintService);
    }

    @Test
    void createReturns400WhenFieldNamesMissing() {
        when(collectionRegistry.get("availability")).thenReturn(availability);

        ResponseEntity<?> response = controller.create(adminRequest, "availability", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(constraintService);
    }

    @Test
    void createReturns400WhenFieldNamesContainsBlank() {
        when(collectionRegistry.get("availability")).thenReturn(availability);

        ResponseEntity<?> response = controller.create(adminRequest, "availability",
                Map.of("fieldNames", java.util.Arrays.asList("title", "")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(constraintService);
    }

    @Test
    void createReturns201WithIndexMetadataOnSuccess() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.create(eq(availability), eq(List.of("title", "provider", "region"))))
                .thenReturn(new CompositeUniqueConstraintService.ConstraintInfo(
                        "uniq_availability_title_provider_region",
                        List.of("title", "provider", "region"),
                        List.of("title", "provider", "region")));

        ResponseEntity<?> response = controller.create(adminRequest, "availability",
                Map.of("fieldNames", List.of("title", "provider", "region")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsEntry("indexName", "uniq_availability_title_provider_region");
        assertThat(data).containsEntry("fieldNames", List.of("title", "provider", "region"));
    }

    @Test
    void createReturns400OnIllegalArgument() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown field 'foo'"));

        ResponseEntity<?> response = controller.create(adminRequest, "availability",
                Map.of("fieldNames", List.of("foo")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createReturns409WhenExistingDataViolatesConstraint() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.create(any(), any()))
                .thenThrow(new UniqueConstraintViolationException(
                        "availability", "title,provider", null));

        ResponseEntity<?> response = controller.create(adminRequest, "availability",
                Map.of("fieldNames", List.of("title", "provider")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listReturnsConstraintsForCollection() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.list(availability)).thenReturn(List.of(
                new CompositeUniqueConstraintService.ConstraintInfo(
                        "uniq_availability_title_provider",
                        List.of("title", "provider"),
                        List.of("title", "provider"))));

        ResponseEntity<?> response = controller.list(adminRequest, "availability");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("indexName", "uniq_availability_title_provider");
    }

    @Test
    void listReturns404WhenCollectionUnknown() {
        when(collectionRegistry.get("nope")).thenReturn(null);
        ResponseEntity<?> response = controller.list(adminRequest, "nope");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void dropReturns204WhenIndexDropped() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.drop(availability, "uniq_availability_title_provider")).thenReturn(true);

        ResponseEntity<?> response = controller.drop(adminRequest, "availability", "uniq_availability_title_provider");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(constraintService).drop(availability, "uniq_availability_title_provider");
    }

    @Test
    void dropReturns404WhenIndexNotFound() {
        when(collectionRegistry.get("availability")).thenReturn(availability);
        when(constraintService.drop(availability, "uniq_missing")).thenReturn(false);

        ResponseEntity<?> response = controller.drop(adminRequest, "availability", "uniq_missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A profile without CUSTOMIZE_APPLICATION (API_ACCESS only, like Portal User) gets 403 on every handler")
    void deniedWithoutCustomizeApplication() {
        controller = new CompositeUniqueConstraintController(collectionRegistry, constraintService,
                AdminPermissionGuardFixture.apiAccessOnly());

        AdminPermissionGuardFixture.assertForbidden(() -> controller.create(adminRequest, "availability",
                Map.of("fieldNames", List.of("a", "b"))), "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.list(adminRequest, "availability"),
                "CUSTOMIZE_APPLICATION");
        AdminPermissionGuardFixture.assertForbidden(() -> controller.drop(adminRequest, "availability", "idx"),
                "CUSTOMIZE_APPLICATION");
        verifyNoInteractions(collectionRegistry, constraintService);
    }

    @Test
    @DisplayName("A profile holding CUSTOMIZE_APPLICATION reaches the handler")
    void allowedWithCustomizeApplication() {
        when(collectionRegistry.get("availability")).thenReturn(availability);

        ResponseEntity<?> response = controller.list(adminRequest, "availability");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
