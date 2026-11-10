package com.test365alm.server.businessfields;

import java.lang.reflect.Modifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.requirement.LegacyRequirementService;
import com.test365alm.server.testcase.TestCaseService;
import com.test365alm.server.testcase.LegacyTestCaseService;
import com.test365alm.server.defect.DefectService;
import com.test365alm.server.defect.LegacyDefectService;

/** Explicit registration only; no component scanning and no production artifact. */
public final class LegacyContentServices {
    private LegacyContentServices() { }
    public static void register(AnnotationConfigApplicationContext context,boolean legacy,boolean defects) {
        register(context,legacy,RequirementService.class,LegacyRequirementService.class);
        register(context,legacy,TestCaseService.class,LegacyTestCaseService.class);
        if(defects)register(context,legacy,DefectService.class,LegacyDefectService.class);
    }
    public static void requirement(AnnotationConfigApplicationContext context,boolean legacy) {
        register(context,legacy,RequirementService.class,LegacyRequirementService.class);
    }
    private static void register(AnnotationConfigApplicationContext context,boolean legacy,Class<?> current,Class<?> fixture) {
        if(legacy)for(var method:current.getDeclaredMethods()) {
            if(!Modifier.isPublic(method.getModifiers())||Modifier.isStatic(method.getModifiers())||method.isSynthetic())continue;
            try { fixture.getDeclaredMethod(method.getName(),method.getParameterTypes()); }
            catch(NoSuchMethodException ex){throw new IllegalStateException("Old schema fixture cannot inherit a current content method: "+method.getName(),ex);}
        }
        context.register(legacy?fixture:current);
    }
}
