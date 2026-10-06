package org.example.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import io.smallrye.config.WithParentName;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Type-safe view of every property under the {@code app.} prefix.
 *
 * <p>SmallRye generates an implementation of this interface at build() time. Method names are
 * mapped to property names using kebab-case, so {@code sampleRate()} reads {@code app.sample-rate}
 * (environment variable {@code APP_SAMPLE_RATE}).
 *
 * <p>The whole mapping is validated eagerly when the config is built: a missing required value or
 * a value that cannot be converted fails startup with a list of every problem, rather than failing
 * later at first use.
 */
@ConfigMapping(prefix = "app")
public interface AppConfig {

  // ---------------------------------------------------------------- Strings

  /** Required: no default, so startup fails if no source provides {@code app.name}. */
  String name();

  /** Optional: absent is a legitimate state, so no default is needed. */
  Optional<String> description();

  /** {@link WithName} overrides the derived key; this reads {@code app.env}. */
  @WithName("env")
  @WithDefault("local")
  String environment();

  /**
   * Read by {@link ConfigFactory} before the full config is built. It is declared here as well
   * because the mapping rejects unknown {@code app.*} keys.
   */
  Optional<String> configFile();

  // ------------------------------------------------------------- Primitives

  @WithDefault("8080")
  int port();

  @WithDefault("false")
  boolean debug();

  @WithDefault("0.25")
  double sampleRate();

  @WithDefault("1048576")
  long maxPayloadBytes();

  /** Optional primitive: use OptionalInt / OptionalLong / OptionalDouble instead of boxing. */
  OptionalInt workerThreads();

  // --------------------------------------- Types with built-in converters

  /** Enums convert case-insensitively, so "warn", "WARN" and "Warn" all work. */
  @WithDefault("info")
  LogLevel logLevel();

  /**
   * Any type with a static {@code of/valueOf/parse(String)} method or a String constructor is
   * converted implicitly. Duration uses {@code Duration.parse}, so values are ISO-8601 (PT30S).
   */
  @WithDefault("PT30S")
  Duration requestTimeout();

  Optional<URI> homepage();

  // -------------------------------------------- Collections of simple values

  /**
   * Lists accept either a comma-separated value ({@code app.tags=a,b,c}) or indexed keys ({@code
   * app.tags[0]=a}); YAML sequences are flattened to indexed keys automatically.
   */
  @WithDefault("untagged")
  List<String> tags();

  // ------------------------------------------ Custom value type (Converter)

  /** Converted by {@link HostPortConverter}, which is registered on the builder. */
  Optional<HostPort> proxy();

  /** The same converter is applied to every element: {@code app.peers=a:1,b:2}. */
  @WithDefault("localhost:7000")
  List<HostPort> peers();

  // ------------------------------------------------------- Complex objects

  /** Nested group: {@code app.server.host}, {@code app.server.port}, ... */
  Server server();

  /** Map of groups, keyed by a name chosen in config: {@code app.datasources.<name>.url}. */
  Map<String, Datasource> datasources();

  /** List of groups: {@code app.endpoints[0].url}, {@code app.endpoints[1].url}, ... */
  List<Endpoint> endpoints();

  /**
   * Free-form key/value pairs: every {@code app.labels.<key>} becomes a map entry. Use this for
   * pass-through settings whose keys are not known at compile time.
   */
  Map<String, String> labels();

  /** Map of free-form groups: {@code app.plugins.<plugin>.<any-key>=value}. */
  Map<String, Plugin> plugins();

  /** Default methods can derive values from mapped ones. */
  default boolean tlsEnabled() {
    return server().tls().isPresent();
  }

  /** Logging level. */
  enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
  }

  /** HTTP server settings. */
  interface Server {
    @WithDefault("localhost")
    String host();

    @WithDefault("8443")
    int port();

    /** Optional nested group: present only if at least one {@code app.server.tls.*} key is set. */
    Optional<Tls> tls();
  }

  /** TLS settings; both values are required once the group exists. */
  interface Tls {
    String keystore();

    String password();
  }

  /** A named JDBC-style datasource. */
  interface Datasource {
    String url();

    @WithDefault("sa")
    String username();

    Optional<String> password();

    @WithDefault("10")
    int poolSize();
  }

  /** A remote endpoint the app calls. */
  interface Endpoint {
    String name();

    URI url();

    @WithDefault("3")
    int retries();

    /** A map inside a list element: {@code app.endpoints[0].headers.Accept=text/plain}. */
    Map<String, String> headers();
  }

  /**
   * Untyped settings for one plugin. {@link WithParentName} means the map is not under its own
   * {@code props} key; every key directly under {@code app.plugins.<name>} becomes an entry.
   */
  interface Plugin {
    @WithParentName
    Map<String, String> props();

    /** Typed accessor layered over the raw map. */
    default boolean enabled() {
      return Boolean.parseBoolean(props().getOrDefault("enabled", "true"));
    }
  }
}
