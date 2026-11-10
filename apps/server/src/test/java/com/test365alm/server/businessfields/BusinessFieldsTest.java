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
        for(String value:java.util.List.of("{\"test_purpose\":{\"value\":\"x\",\"revision\":18446744073709551617}}", "{\"test_purpose\":{\"value\":\"x\",\"revision\":2}}", "{\"test_purpose\":{\"value\":\"x\",\"fieldId\":\"bad\"}}", "{\"test_purpose\":{\"value\":\"x\",\"unknown\":true}}"))
            assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("test_case",CoreCommands.JSON.readTree(value),null,null));
    }
    @Test void customTypesDefaultsAndExactDefinitionVersionsAreValidated() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var project=org.mockito.Mockito.mock(com.test365alm.server.project.ProjectService.ProjectView.class);
        org.mockito.Mockito.when(project.tenantId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(project.id()).thenReturn(java.util.UUID.randomUUID());
        var id=java.util.UUID.randomUUID();
        var definition=CoreCommands.JSON.readTree("{\"key\":\"equipment_system_id\",\"type\":\"NUMBER\",\"required\":true,\"precision\":5,\"scale\":2,\"minimum\":\"0\",\"maximum\":\"100\",\"defaultValue\":\"5.00\"}");
        var tenant=project.tenantId();var projectId=project.id();
        org.mockito.Mockito.doReturn(java.util.List.of(definition)).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.eq(tenant),org.mockito.ArgumentMatchers.eq(projectId),org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.eq("equipment_system_id"),org.mockito.ArgumentMatchers.eq(3L));
        var entry=CoreCommands.JSON.createObjectNode().put("fieldId",id.toString()).put("revision",3);
        var input=CoreCommands.JSON.createObjectNode().set("equipment_system_id",entry);
        assertEquals("5.00",BusinessFields.normalize("requirement",input,jdbc,project).path("equipment_system_id").path("value").asText());
        for(String bad:java.util.List.of("100.01","1.001","1e2","-1")) {
            entry.put("value",bad);assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("requirement",input,jdbc,project));
        }
        entry.put("value","10.00");entry.set("definition",CoreCommands.JSON.createObjectNode());
        assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("requirement",input,jdbc,project));
    }
    @Test void missingValueNullAndInvalidReviewerCannotSilentlyBecomeValid() {
        var old=new com.test365alm.server.defect.DefectService.Revision(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),1,"Old","body","HIGH","HIGH","OPEN","",java.util.UUID.randomUUID(),java.time.OffsetDateTime.parse("2026-11-10T12:00:00Z"));
        var replay=CoreCommands.JSON.readValue(CoreCommands.JSON.writeValueAsString(old),com.test365alm.server.defect.DefectService.Revision.class);
        assertEquals(old.businessFields(),replay.businessFields(),"legacy optional fields must round trip without changing the frozen response");
        assertEquals(old,replay);
        var at=old.createdAt();var actor=old.createdBy();var id=old.id();var parent=old.defectId();
        for(Object snapshot:java.util.List.of(old,
            new com.test365alm.server.testcase.TestCaseService.RevisionView(id,parent,1,"Old","body","",at,actor,java.util.List.of()),
            new com.test365alm.server.requirement.RequirementService.RequirementView(id,parent,1,1,at,actor,id,1,"Old","body","HIGH"),
            new com.test365alm.server.requirement.RequirementService.RevisionView(id,parent,1,"Old","body","HIGH",at,actor))) {
            assertEquals(snapshot,CoreCommands.JSON.readValue(CoreCommands.JSON.writeValueAsString(snapshot),snapshot.getClass()));
            String kind=snapshot instanceof com.test365alm.server.defect.DefectService.Revision?"defect":snapshot instanceof com.test365alm.server.testcase.TestCaseService.RevisionView?"test_case":"requirement";
            String field=kind.equals("defect")?"occurrence_department":kind.equals("test_case")?"test_purpose":"equipment_system_id";
            var values=CoreCommands.JSON.createObjectNode();values.set(field,CoreCommands.JSON.createObjectNode().put("value","Frozen value"));
            for(var frozen:java.util.List.of(BusinessFields.empty(),BusinessFields.normalize(kind,values,null,null))) {
                var response=(tools.jackson.databind.node.ObjectNode)CoreCommands.JSON.readTree(CoreCommands.JSON.writeValueAsString(snapshot));response.set("businessFields",frozen);
                var decoded=CoreCommands.JSON.readValue(response.toString(),snapshot.getClass());
                assertEquals(frozen,CoreCommands.JSON.readTree(CoreCommands.JSON.writeValueAsString(decoded)).get("businessFields"));
                assertEquals(decoded,CoreCommands.JSON.readValue(CoreCommands.JSON.writeValueAsString(decoded),snapshot.getClass()));
            }
        }
        for(String json:java.util.List.of("null","[]","{\"reviewer\":{\"value\":\"display name\"}}","{\"test_purpose\":{\"value\":\"x\",\"kind\":\"CUSTOM\"}}"))
            assertThrows(ProjectAccessException.class,()->BusinessFields.normalize("test_case",CoreCommands.JSON.readTree(json),null,null));
        assertTrue(BusinessFields.normalize("requirement",null,null,null).isEmpty());
    }
}
