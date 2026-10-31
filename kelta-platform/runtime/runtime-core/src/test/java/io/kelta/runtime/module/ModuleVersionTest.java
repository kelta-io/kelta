package io.kelta.runtime.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleVersion")
class ModuleVersionTest {

    @Test
    @DisplayName("parses MAJOR.MINOR.PATCH with an optional v, pre-release and build")
    void parses() {
        assertThat(ModuleVersion.parse("0.4.0")).isEqualTo(new ModuleVersion(0, 4, 0, null));
        assertThat(ModuleVersion.parse("v1.2.3")).isEqualTo(new ModuleVersion(1, 2, 3, null));
        assertThat(ModuleVersion.parse("2.0.0-rc.1+build.7"))
                .isEqualTo(new ModuleVersion(2, 0, 0, "rc.1"));
        assertThat(ModuleVersion.parse("2.0.0-rc.1+build.7").toString()).isEqualTo("2.0.0-rc.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.x", "latest", "1.2.3-", "1.2.3+"})
    @DisplayName("refuses anything that is not a semantic version")
    void refuses(String value) {
        assertThat(ModuleVersion.isValid(value)).isFalse();
        assertThatThrownBy(() -> ModuleVersion.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("orders by semver precedence, ignoring build metadata")
    void ordersBySemverPrecedence() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0", "10.0.0");
        List<String> shuffled = Stream.of("10.0.0", "1.0.0-beta.11", "1.0.0", "1.0.0-alpha.beta", "2.0.0",
                "1.0.0-rc.1", "1.1.0", "1.0.0-alpha", "1.0.1", "1.0.0-beta.2", "1.0.0-alpha.1", "1.0.0-beta")
                .map(ModuleVersion::parse).sorted().map(ModuleVersion::toString).toList();
        assertThat(shuffled).isEqualTo(ordered);
        assertThat(ModuleVersion.parse("1.0.0+a").compareTo(ModuleVersion.parse("1.0.0+b"))).isZero();
    }
}
