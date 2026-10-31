package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumerAlertingSystemCollections")
class ConsumerAlertingSystemCollectionsTest {

    @Test
    @DisplayName("watch-targets exposes a non-null synthetic flag defaulting to false")
    void watchTargetsExposeSyntheticFlag() {
        CollectionDefinition targets = ConsumerAlertingSystemCollections.watchTargets();

        FieldDefinition synthetic = targets.getField("synthetic");

        assertThat(synthetic).isNotNull();
        assertThat(synthetic.type()).isEqualTo(FieldType.BOOLEAN);
        assertThat(synthetic.nullable()).isFalse();
        assertThat(synthetic.defaultValue()).isEqualTo(false);
        assertThat(synthetic.effectiveColumnName()).isEqualTo("synthetic");
        assertThat(synthetic.description()).isNotBlank();
    }
}
