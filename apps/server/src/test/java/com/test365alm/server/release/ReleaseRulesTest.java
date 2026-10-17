package com.test365alm.server.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

class ReleaseRulesTest {
    @Test void strictDatesTypesAndClosedFieldsHaveDeterministicErrors() {
        var valid=ReleaseRules.release(CoreCommands.JSON.readTree("{\"name\":\" R \",\"startDate\":\"2028-02-29\",\"endDate\":\"2028-03-01\"}"),null);
        assertEquals("R",valid.name());
        assertEquals("PLANNED",valid.status());
        for(String body:new String[]{"{\"name\":1,\"startDate\":\"2028-02-29\",\"endDate\":\"2028-03-01\"}","{\"name\":\"R\",\"startDate\":\"2028-02-29\",\"endDate\":\"2028-03-01\",\"createdBy\":\"fake\"}"})
            assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->ReleaseRules.release(CoreCommands.JSON.readTree(body),null)).code());
        for(String date:new String[]{"2027-02-29","2028-2-29","2028-03-02"})
            assertEquals("VALIDATION_FAILED",assertThrows(ProjectAccessException.class,()->ReleaseRules.release(CoreCommands.JSON.readTree("{\"name\":\"R\",\"startDate\":\""+date+"\",\"endDate\":\"2028-03-01\"}"),null)).code());
    }
    @Test void finiteLifecycleAndReadonlyContentCannotBeReopened() {
        var planned=ReleaseRules.release(CoreCommands.JSON.readTree("{\"name\":\"R\",\"startDate\":\"2028-01-01\",\"endDate\":\"2028-12-31\"}"),null);
        var closed=ReleaseRules.release(CoreCommands.JSON.readTree("{\"status\":\"CLOSED\"}"),planned);
        assertEquals("RELEASE_READ_ONLY",assertThrows(ProjectAccessException.class,()->ReleaseRules.release(CoreCommands.JSON.readTree("{\"name\":\"edited\"}"),closed)).code());
        assertEquals("ARCHIVED",ReleaseRules.release(CoreCommands.JSON.readTree("{\"status\":\"ARCHIVED\"}"),closed).status());
        assertEquals("INVALID_TRANSITION",assertThrows(ProjectAccessException.class,()->ReleaseRules.release(CoreCommands.JSON.readTree("{\"status\":\"PLANNED\"}"),closed)).code());
        assertEquals("INVALID_REQUEST",assertThrows(ProjectAccessException.class,()->ReleaseRules.release(CoreCommands.JSON.readTree("{\"status\":\"unexpected\"}"),planned)).code());
        var cycle=ReleaseRules.cycle(CoreCommands.JSON.readTree("{\"name\":\"C\",\"startDate\":\"2028-01-01\",\"endDate\":\"2028-01-31\"}"),null);
        var cycleClosed=ReleaseRules.cycle(CoreCommands.JSON.readTree("{\"status\":\"CLOSED\"}"),cycle);
        assertEquals("CYCLE_READ_ONLY",assertThrows(ProjectAccessException.class,()->ReleaseRules.cycle(CoreCommands.JSON.readTree("{\"name\":\"new\"}"),cycleClosed)).code());
    }
}
