package com.test365alm.server.report;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.test365alm.server.project.ProjectAccessException;
class ReportRulesTest {
 @Test void mutuallyExclusiveCategoriesAndExplicitRateDenominators(){
  var c=ReportRules.counts(List.of("NOT_RUN","RUNNING","PAUSED","PASS","FAIL","BLOCKED","PASS"));
  assertEquals(7,c.categories().values().stream().mapToLong(Long::longValue).sum());
  assertEquals(4.0/7,c.completionRate());assertEquals(2.0/4,c.passRate());
  assertNull(ReportRules.counts(List.of()).completionRate());assertNull(ReportRules.counts(List.of("RUNNING")).passRate());
 }
 @Test void limitsFailInsteadOfTruncatingAndFiltersAreWhitelisted(){
  ReportRules.limit(1000,1000);assertThrows(ProjectAccessException.class,()->ReportRules.limit(1001,1000));
  assertNull(ReportRules.filter("",ReportRules.STATES));assertThrows(ProjectAccessException.class,()->ReportRules.filter("FINISHED",ReportRules.STATES));
 }
}
