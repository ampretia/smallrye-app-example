package org.example.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.SmallRyeConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each test builds an isolated config from explicit args / env / system properties, so nothing
 * depends on the machine running the build.
 */
class ConfigFactoryTest {

  private static AppConfig build(
      final String[] args, final Map<String, String> env, final Map<String, String> sys) {
    final SmallRyeConfig config = ConfigFactory.create(args, env, sys);
    return config.getConfigMapping(AppConfig.class);
  }

  private static AppConfig args(final String... args) {
    return build(args, Map.of(), Map.of());
  }

  private static AppConfig env(final Map<String, String> env) {
    return build(new String[0], env, Map.of());
  }

  @Nested
  class Defaults {
    @Test
    void classpathYamlSuppliesValues() {
      final AppConfig app = args();
      assertEquals("smallrye-example", app.name());
      assertEquals(8080, app.port());
      assertEquals(0.5, app.sampleRate());
      assertEquals(List.of("demo", "config"), app.tags());
      assertEquals(Duration.ofSeconds(10), app.requestTimeout());
    }

    @Test
    void withDefaultUsedWhenNoSourceHasTheKey() {
      final AppConfig app = args();
      assertEquals("local", app.environment());
      assertEquals(3, app.endpoints().get(0).retries());
      assertEquals(10, app.datasources().get("customers").poolSize());
    }

    @Test
    void optionalsAreEmptyWhenUnset() {
      final AppConfig app = args();
      assertTrue(app.workerThreads().isEmpty());
      assertTrue(app.proxy().isEmpty());
      assertTrue(app.server().tls().isEmpty());
      assertFalse(app.tlsEnabled());
    }

    @Test
    void expressionsAreExpanded() {
      assertEquals(
          "smallrye-example (local) listening on 0.0.0.0:8443", args().description().orElseThrow());
    }
  }

  @Nested
  class Priority {
    @Test
    void commandLineBeatsSystemPropertyBeatsEnvironment() {
      final Map<String, String> env =
          Map.of("APP_PORT", "1", "APP_SAMPLE_RATE", "0.1", "APP_MAX_PAYLOAD_BYTES", "11");
      final Map<String, String> sys = Map.of("app.port", "2", "app.sample-rate", "0.2");
      final AppConfig app = build(new String[] {"--app.port=3"}, env, sys);

      assertEquals(3, app.port(), "CLI (500) wins");
      assertEquals(0.2, app.sampleRate(), "system property (400) beats env (300)");
      assertEquals(11L, app.maxPayloadBytes(), "env (300) beats classpath file (250)");
    }

    @Test
    void sourcesMergePerKeyRatherThanReplacingEachOther() {
      final AppConfig app = env(Map.of("APP_SERVER_HOST", "env-host"));
      assertEquals("env-host", app.server().host());
      assertEquals(8443, app.server().port(), "sibling key still from application.yaml");
    }
  }

  @Nested
  class CommandLine {
    @Test
    void supportsKeyValueAndBareFlags() {
      final AppConfig app = args("--app.port=9000", "--app.log-level=warn", "--app.debug");
      assertEquals(9000, app.port());
      assertEquals(AppConfig.LogLevel.WARN, app.logLevel());
      assertTrue(app.debug());
    }

    @Test
    void unrecognisedArgumentsArePositional() {
      final CommandLineConfigSource src =
          new CommandLineConfigSource("input.csv", "--app.port=1", "-v", "app.debug=true");
      assertEquals(List.of("input.csv", "-v", "app.debug=true"), src.positional());
      assertEquals("1", src.getValue("app.port"));
    }

    @Test
    void valueMayContainEquals() {
      // e.g. base64 padding
      assertEquals("YWJj==", new CommandLineConfigSource("--k=YWJj==").getValue("k"));
    }
  }

  @Nested
  class EnvironmentVariables {
    @Test
    void primitivesAndDashedNames() {
      final AppConfig app =
          env(Map.of(
              "APP_DEBUG", "true",
              "APP_WORKER_THREADS", "4",
              "APP_LOG_LEVEL", "error",
              "APP_ENV", "ci"));
      assertTrue(app.debug());
      assertEquals(4, app.workerThreads().getAsInt());
      assertEquals(AppConfig.LogLevel.ERROR, app.logLevel());
      assertEquals("ci", app.environment());
    }

    @Test
    void mapOfGroupsCanBeExtendedAndOverridden() {
      final AppConfig app =
          env(Map.of(
              "APP_DATASOURCES_ORDERS_POOL_SIZE", "99",
              "APP_DATASOURCES_REPORTING_URL", "jdbc:x://r"));
      assertEquals(99, app.datasources().get("orders").poolSize());
      assertEquals("jdbc:x://r", app.datasources().get("reporting").url());
      assertEquals(3, app.datasources().size());
    }

    @Test
    void indexedListElementUsesDoubleUnderscore() {
      // app.endpoints[1].retries -> APP_ENDPOINTS_1__RETRIES ('[' and ']' both become '_')
      final AppConfig app = env(Map.of("APP_ENDPOINTS_1__RETRIES", "42"));
      assertEquals(42, app.endpoints().get(1).retries());
    }

    @Test
    void optionalGroupAppearsWhenItsKeysAreSet() {
      final AppConfig app =
          env(Map.of("APP_SERVER_TLS_KEYSTORE", "/k", "APP_SERVER_TLS_PASSWORD", "p"));
      assertEquals("/k", app.server().tls().orElseThrow().keystore());
      assertTrue(app.tlsEnabled());
    }
  }

  @Nested
  class ExternalFile {
    @Test
    void yamlFileOverridesPackagedFile(@TempDir final Path dir) throws IOException {
      final Path file =
          Files.writeString(
              dir.resolve("x.yaml"),
              """
              app:
                port: 81
                homepage: https://override.example
                datasources:
                  extra:
                    url: jdbc:e
              """);
      final AppConfig app = args("--app.config-file=" + file);
      assertEquals(81, app.port());
      assertEquals(URI.create("https://override.example"), app.homepage().orElseThrow());
      assertEquals(3, app.datasources().size(), "map entries merge across files");
    }

    @Test
    void propertiesFileSelectedByEnvironmentVariable(@TempDir final Path dir) throws IOException {
      final Path file =
          Files.writeString(
              dir.resolve("x.properties"),
              """
              app.tags=a,b,c
              app.labels.region=eu-de
              app.plugins.s3.region=us-south
              """);
      final AppConfig app = env(Map.of("APP_CONFIG_FILE", file.toString()));
      assertEquals(List.of("a", "b", "c"), app.tags(), "comma form beats lower-priority list");
      assertEquals("eu-de", app.labels().get("region"));
      assertEquals("us-south", app.plugins().get("s3").props().get("region"));
    }

    @Test
    void environmentBeatsExternalFile(@TempDir final Path dir) throws IOException {
      final Path file = Files.writeString(dir.resolve("x.properties"), "app.port=81\n");
      final AppConfig app = env(Map.of("APP_CONFIG_FILE", file.toString(), "APP_PORT", "82"));
      assertEquals(82, app.port());
    }

    @Test
    void missingFileFailsFast() {
      assertThrows(IllegalArgumentException.class, () -> args("--app.config-file=/nope.yaml"));
    }
  }

  @Nested
  class ComplexTypes {
    @Test
    void customConverterAppliesToSingleValuesAndLists() {
      final AppConfig app = args("--app.proxy=p.corp:3128", "--app.peers=a:1,b:2");
      assertEquals(new HostPort("p.corp", 3128), app.proxy().orElseThrow());
      assertEquals(List.of(new HostPort("a", 1), new HostPort("b", 2)), app.peers());
    }

    @Test
    void withParentNameCollectsArbitraryKeys() {
      final AppConfig app = args("--app.plugins.cache.ttl=60");
      assertEquals(
          Map.of("bucket", "example-bucket", "region", "eu-de"), app.plugins().get("s3").props());
      assertEquals(Map.of("ttl", "60"), app.plugins().get("cache").props());
      assertFalse(app.plugins().get("metrics").enabled());
    }

    @Test
    void mapInsideListElement() {
      assertEquals("application/json", args().endpoints().get(0).headers().get("Accept"));
    }
  }

  @Nested
  class Profiles {
    @Test
    void profileOverridesOnlyListedKeys() {
      final AppConfig app = args("--smallrye.config.profile=dev");
      assertEquals("dev", app.environment());
      assertTrue(app.debug());
      assertEquals("localhost", app.server().host());
      assertEquals(8443, app.server().port(), "non-profile value kept");
    }

    @Test
    void profileFromEnvironment() {
      assertEquals("dev", env(Map.of("SMALLRYE_CONFIG_PROFILE", "dev")).environment());
    }
  }

  @Nested
  class Validation {
    @Test
    void allProblemsReportedTogether() {
      final ConfigValidationException e =
          assertThrows(
              ConfigValidationException.class,
              () -> args("--app.port=abc", "--app.proxy=nocolon", "--app.server.tls.keystore=/k"));
      assertEquals(3, e.getProblemCount());
    }

    @Test
    void unknownKeyUnderPrefixIsRejected() {
      final ConfigValidationException e =
          assertThrows(ConfigValidationException.class, () -> args("--app.nmae=typo"));
      assertTrue(e.getProblem(0).getMessage().contains("app.nmae"));
    }

    @Test
    void keysOutsideThePrefixAreIgnored() {
      assertEquals(8080, args("--other.thing=1").port());
    }
  }
}
