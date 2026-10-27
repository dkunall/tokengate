package com.tokengate.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Package layout every app uses:
 * <pre>
 *   adapter.in.*   web, messaging, schedulers   -> application
 *   application    use cases, port.out          -> domain
 *   adapter.out.*  redis, jdbc, http, kafka     implements application.port.out
 *   config         the only place that names concrete adapters
 * </pre>
 * domain and application stay plain Java so the interesting logic is testable without a Spring context.
 */
public final class HexagonalRules {

    private static final String[] FRAMEWORKS = {"org.springframework..", "jakarta.servlet..", "jakarta.persistence..",
            "com.fasterxml.jackson..", "org.apache.kafka..", "io.lettuce..", "io.micrometer..", "ai.onnxruntime.."};

    private HexagonalRules() {
    }

    public static List<ArchRule> rules() {
        return List.of(
                noClasses().that().resideInAPackage("..domain..")
                        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..")
                        .allowEmptyShould(true).as("domain depends on nothing outside itself"),
                noClasses().that().resideInAPackage("..domain..")
                        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
                        .allowEmptyShould(true).as("domain is framework-free"),
                noClasses().that().resideInAPackage("..application..")
                        .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..")
                        .allowEmptyShould(true).as("application does not know adapters or wiring"),
                noClasses().that().resideInAPackage("..application..")
                        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
                        .allowEmptyShould(true).as("application is framework-free"),
                noClasses().that().resideInAPackage("..adapter.in..")
                        .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                        .allowEmptyShould(true).as("inbound adapters go through use cases"),
                noClasses().that().resideInAPackage("..adapter.out..")
                        .should().dependOnClassesThat().resideInAPackage("..adapter.in..")
                        .allowEmptyShould(true).as("outbound adapters never call inbound adapters"),
                classes().that().resideInAPackage("..application.port.out..").and().areTopLevelClasses()
                        .and().areNotRecords().and().areNotEnums()
                        .should().beInterfaces()
                        .allowEmptyShould(true).as("outbound ports are interfaces"),
                noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                        .allowEmptyShould(true).as("constructor injection only"));
    }

    public static void check(String basePackage) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
        if (classes.isEmpty()) {
            throw new AssertionError("no classes found under " + basePackage);
        }
        rules().forEach(r -> r.check(classes));
    }
}
