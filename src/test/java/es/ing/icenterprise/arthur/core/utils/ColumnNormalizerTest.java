package es.ing.icenterprise.arthur.core.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnNormalizerTest {

    @ParameterizedTest
    @DisplayName("Should normalize various column name formats to UPPER_SNAKE_CASE")
    @CsvSource({
        "'Nombre Producto',    NOMBRE_PRODUCTO",
        "'código región',      CODIGO_REGION",
        "'First Name',         FIRST_NAME",
        "'camelCaseField',     CAMEL_CASE_FIELD",
        "'ALREADY_UPPER',      ALREADY_UPPER",
        "'with-dashes',        WITH_DASHES",
        "'with.dots',          WITH_DOTS",
        "'  spaces  around  ', SPACES_AROUND",
        "'José María',         JOSE_MARIA",
        "'año_nacimiento',     ANO_NACIMIENTO",
        "'über cool field',    UBER_COOL_FIELD",
        "'ID',                 ID",
        "'a',                  A"
    })
    void shouldNormalizeColumnNames(String input, String expected) {
        assertThat(ColumnNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should return null for null input")
    void shouldReturnNullForNull() {
        assertThat(ColumnNormalizer.normalize(null)).isNull();
    }

    @Test
    @DisplayName("Should return null for blank input")
    void shouldReturnNullForBlank() {
        assertThat(ColumnNormalizer.normalize("   ")).isNull();
    }
}
