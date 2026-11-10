package com.test365alm.server.folder;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.test365alm.server.project.ProjectAccessException;

class FolderServiceTest {
    @Test void namesAreTrimmedAndBounded() {
        assertEquals("OQ",FolderService.name(" OQ "));
        for (String value:new String[]{null," ","a".repeat(201)}) assertThrows(ProjectAccessException.class,()->FolderService.name(value));
    }
    @Test void versionsRequireOneStrongPositiveTag() {
        assertEquals(12,FolderService.version("\"12\""));
        assertEquals(428,assertThrows(ProjectAccessException.class,()->FolderService.version(null)).status().value());
        for (String value:new String[]{"1","*","W/\"1\"","\"0\"","\"9999999999999999999999\""}) assertThrows(ProjectAccessException.class,()->FolderService.version(value));
    }
    @Test void invalidKindsSortAndFiltersAreRejected() {
        assertThrows(ProjectAccessException.class,()->FolderService.kind("OTHER"));
        assertThrows(ProjectAccessException.class,()->FolderService.order(-1));
        assertThrows(ProjectAccessException.class,()->FolderService.filter("bad"));
        FolderService.filter("root");FolderService.filter(null);
    }
}
