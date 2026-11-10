package com.test365alm.server.businessfields;

import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessFieldsTest {
    @Test void systemValuesAreTypedBoundedAndRevisioned() {
        var n=CoreCommands.JSON.readTree("{\"test_purpose\":{\"value\":\"Verify pump\"}}");
        var saved=BusinessFields.normalize("test_case",n,null,null);
        assertEquals("Verify pump",saved.path("test_purpose").path("value").asText());
        assertEquals(1,saved.path("test_purpose").path("revision").asInt());
        assertEquals(saved,BusinessFields.normalize("test_case",saved,null,null));
        assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("test_case",CoreCommands.JSON.readTree("{\"title\":{\"value\":\"x\"}}"),null,null));
        assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("test_case",CoreCommands.JSON.readTree("{\"test_purpose\":{\"value\":5}}"),null,null));
        assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("requirement",CoreCommands.JSON.readTree("{\"equipment_system_id\":{\"value\":\""+"x".repeat(201)+"\"}}"),null,null));
    }
    @Test void forgedDefinitionsAndIncompleteCustomReferencesAreRejected() {
        for(String value:java.util.List.of("{\"test_purpose\":{\"value\":\"x\",\"revision\":2}}", "{\"test_purpose\":{\"value\":\"x\",\"fieldId\":\"bad\"}}", "{\"test_purpose\":{\"value\":\"x\",\"unknown\":true}}"))
            assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("test_case",CoreCommands.JSON.readTree(value),null,null));
    }
}
