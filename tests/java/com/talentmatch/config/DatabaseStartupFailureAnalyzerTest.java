package com.talentmatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.mock.env.MockEnvironment;

class DatabaseStartupFailureAnalyzerTest {

    private static MockEnvironment env() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example:5433/tmdb?sslmode=require")
                .withProperty("spring.datasource.username", "tm_user")
                .withProperty("spring.datasource.password", "s3cr3t-pw");
    }

    @Test
    void connectExceptionInCauseChainIsExplained() {
        Throwable failure = new BeanCreationException("dataSource",
                new IllegalStateException("wrapped", new ConnectException("Connection refused")));
        FailureAnalysis a = new DatabaseStartupFailureAnalyzer(env()).analyze(failure);
        assertThat(a).isNotNull();
        assertThat(a.getDescription())
                .startsWith("Could not connect to PostgreSQL at db.example:5433/tmdb as tm_user.")
                .doesNotContain("s3cr3t-pw");
        assertThat(a.getAction()).isEqualTo("Start it with `bash scripts/start_db.sh`, or set "
                + "DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD.");
        assertThat(a.getCause()).isSameAs(failure);
    }

    @Test
    void sqlStatesAreRecognised() {
        DatabaseStartupFailureAnalyzer analyzer = new DatabaseStartupFailureAnalyzer(env());
        for (String state : List.of("08001", "28P01", "3D000")) {
            FailureAnalysis a = analyzer.analyze(new RuntimeException(new SQLException("boom", state)));
            assertThat(a).as(state).isNotNull();
            assertThat(a.getDescription()).doesNotContain("s3cr3t-pw");
        }
        assertThat(analyzer.analyze(new SQLException("bad pw", "28P01")).getDescription())
                .contains("authentication failed");
        assertThat(analyzer.analyze(new SQLException("no db", "3D000")).getDescription())
                .contains("does not exist");
    }

    @Test
    void unrelatedFailuresAreIgnored() {
        DatabaseStartupFailureAnalyzer analyzer = new DatabaseStartupFailureAnalyzer(env());
        assertThat(analyzer.analyze(new IllegalStateException("nope"))).isNull();
        assertThat(analyzer.analyze(new SQLException("syntax", "42601"))).isNull();
    }

    @Test
    void defaultsWhenUrlIsUnparseableAndEnvironmentMissing() {
        FailureAnalysis a = new DatabaseStartupFailureAnalyzer(null).analyze(new ConnectException("x"));
        assertThat(a.getDescription()).startsWith("Could not connect to PostgreSQL at localhost:5432/talentmatch as (unset).");
    }

    @Test
    void registeredInSpringFactoriesAndInstantiableWithEnvironment() {
        // Same loading mechanism as Spring Boot's FailureAnalyzers (constructor injection of Environment).
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ArgumentResolver resolver = ArgumentResolver.of(BeanFactory.class, beanFactory)
                .and(Environment.class, env());
        List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(FailureAnalyzer.class, resolver, (type, impl, failure) -> {
                    // Boot also skips analyzers whose optional dependencies (e.g. Liquibase) are absent.
                    if (impl.equals(DatabaseStartupFailureAnalyzer.class.getName())) {
                        throw new AssertionError("could not instantiate " + impl, failure);
                    }
                });
        assertThat(analyzers).anySatisfy(a -> assertThat(a).isInstanceOf(DatabaseStartupFailureAnalyzer.class));
        FailureAnalyzer ours = analyzers.stream().filter(DatabaseStartupFailureAnalyzer.class::isInstance)
                .findFirst().orElseThrow();
        assertThat(ours.analyze(new ConnectException("x")).getDescription()).contains("db.example:5433/tmdb");
    }
}
