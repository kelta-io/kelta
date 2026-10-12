package io.kelta.gateway.authz;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChildResourcePathTest {

    private static Optional<ChildResourcePath> parse(String path) {
        return ChildResourcePath.parse(PathContainer.parsePath(path));
    }

    @Test
    void threeSegmentsIsAChildList() {
        ChildResourcePath child = parse("/api/accounts/a1/contacts").orElseThrow();
        assertThat(child.childName()).isEqualTo("contacts");
        assertThat(child.childId()).isNull();
        assertThat(child.directPath()).isEqualTo("/api/contacts");
    }

    @Test
    void fourSegmentsIsAChildRecord() {
        ChildResourcePath child = parse("/api/accounts/a1/contacts/c1").orElseThrow();
        assertThat(child.directPath()).isEqualTo("/api/contacts/c1");
    }

    @Test
    void otherDepthsAreNotChildPaths() {
        assertThat(parse("/api/accounts")).isEmpty();
        assertThat(parse("/api/accounts/a1")).isEmpty();
        assertThat(parse("/api/accounts/download/t/c/o/file.png")).isEmpty();
        assertThat(parse("/other/accounts/a1/contacts")).isEmpty();
    }

    @Test
    void childNameIsReadTheWayTheWorkerMatchesIt() {
        assertThat(parse("/api/accounts/a1/c%6Fntacts").orElseThrow().childName()).isEqualTo("contacts");
        assertThat(parse("/api/accounts/a1/contacts;v=1").orElseThrow().childName()).isEqualTo("contacts");
    }

    @Test
    void encodedSeparatorInTheChildNameIsNotAddressable() {
        assertThat(parse("/api/accounts/a1/contacts%2Fx").orElseThrow().isAddressable()).isFalse();
        assertThat(parse("/api/accounts/a1/contacts").orElseThrow().isAddressable()).isTrue();
    }
}
