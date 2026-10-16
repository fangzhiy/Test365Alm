package com.test365alm.server.defect;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.core.CoreCommands;

class DefectRulesTest {
    @Test void fixedTransitionsAndResolutionEvidenceAreRequired() {
        for(String edge:java.util.List.of("OPEN:IN_PROGRESS","OPEN:RESOLVED","IN_PROGRESS:RESOLVED","RESOLVED:CLOSED","RESOLVED:REOPENED","REOPENED:IN_PROGRESS","REOPENED:RESOLVED")) {
            var s=edge.split(":");assertDoesNotThrow(()->DefectRules.transition(s[0],s[1],"Fixed"));
        }
        assertEquals("INVALID_TRANSITION",assertThrows(ProjectAccessException.class,()->DefectRules.transition("OPEN","CLOSED","x")).code());
        assertThrows(ProjectAccessException.class,()->DefectRules.transition("CLOSED","REOPENED","x"));
        assertThrows(ProjectAccessException.class,()->DefectRules.transition("OPEN","RESOLVED"," "));
    }
    @Test void contentRejectsUnknownFieldsWrongTypesAndInvalidBoundaries() {
        var valid=CoreCommands.JSON.readTree("{\"title\":\"Bug\",\"description\":\"Text\",\"severity\":\"HIGH\",\"priority\":\"MEDIUM\"}");
        assertEquals("Bug",DefectRules.content(valid).title());
        for(String bad:java.util.List.of(valid.toString().replace("Bug",""),valid.toString().replace("HIGH","CUSTOM"),valid.toString().replace("\"Bug\"","9"),valid.toString().replace("\"title\"","\"author\"")))
            assertThrows(ProjectAccessException.class,()->DefectRules.content(CoreCommands.JSON.readTree(bad)));
    }
}
