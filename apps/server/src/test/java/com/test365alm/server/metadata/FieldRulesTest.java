package com.test365alm.server.metadata;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.test365alm.server.project.ProjectAccessException;

class FieldRulesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Test void reservedNamesAndUnknownTypesAreRejected() {
        for (String key : new String[]{"title", "id", "row_version", "created_by"})
            assertThrows(ProjectAccessException.class, () -> FieldRules.definition(JSON.readTree(
                "{\"key\":\""+key+"\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":20}")));
        assertThrows(ProjectAccessException.class, () -> FieldRules.definition(JSON.readTree(
            "{\"key\":\"custom_one\",\"type\":\"SQL\",\"required\":false}")));
    }
    @Test void numberPrecisionScaleBoundsAndTypesAreExact() {
        var d = FieldRules.definition(JSON.readTree("""
            {"key":"cost","type":"NUMBER","required":true,"precision":5,"scale":2,"minimum":"-10.00","maximum":"100.00"}
            """));
        assertEquals("12.34", FieldRules.value(d, JSON.readTree("\"12.34\"")).textValue());
        for(String value : new String[]{"null", "12.34", "\"12.345\"", "\"101.00\"", "\"-10.01\"", "\"1e2\""})
            assertThrows(ProjectAccessException.class, () -> FieldRules.value(d, JSON.readTree(value)));
    }
    @Test void enumTextRequiredDefaultsAndUnknownFieldsAreValidated() {
        var d = FieldRules.definition(JSON.readTree("""
            {"key":"colour","type":"ENUM","required":true,"options":["RED","BLUE"],"defaultValue":"RED"}
            """));
        assertEquals("RED", FieldRules.value(d, null).textValue());
        assertThrows(ProjectAccessException.class, () -> FieldRules.value(d, JSON.readTree("\"GREEN\"")));
        var text = FieldRules.definition(JSON.readTree("{\"key\":\"note\",\"type\":\"TEXT\",\"required\":true,\"maxLength\":3}"));
        for(String value : new String[]{"\"four\"", "\"\"", "true"})
            assertThrows(ProjectAccessException.class, () -> FieldRules.value(text, JSON.readTree(value)));
        assertThrows(ProjectAccessException.class, () -> FieldRules.definition(JSON.readTree("{\"key\":\"note\",\"type\":\"TEXT\",\"required\":false,\"maxLength\":3,\"sql\":\"x\"}")));
    }
}
