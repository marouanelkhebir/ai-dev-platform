package com.mel.aidev.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuildProfileTest {

    @Test
    void detectsAngularFromAngularConfiguration() {
        assertThat(BuildProfile.detect(context("angular.json", "package.json"))).isEqualTo(BuildProfile.ANGULAR);
    }

    @Test
    void detectsMavenFromPom() {
        assertThat(BuildProfile.detect(context("pom.xml", "src/main/java/App.java"))).isEqualTo(BuildProfile.MAVEN);
    }

    @Test
    void detectsPythonFromPyproject() {
        assertThat(BuildProfile.detect(context("pyproject.toml", "src/example/__init__.py")))
                .isEqualTo(BuildProfile.PYTHON);
    }

    @Test
    void detectsPythonFromRequirementsFile() {
        assertThat(BuildProfile.detect(context("requirements.txt", "app.py", "test_app.py")))
                .isEqualTo(BuildProfile.PYTHON);
    }

    @Test
    void rejectsUnknownRepositoryTypes() {
        assertThat(BuildProfile.detect(context("Gemfile", "app/main.rb"))).isEqualTo(BuildProfile.UNSUPPORTED);
    }

    private static RepositoryContext context(String... paths) {
        return new RepositoryContext("group/project", "main", List.of(paths), "", "", List.of(), RepositoryRules.empty());
    }
}
