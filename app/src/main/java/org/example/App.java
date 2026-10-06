package org.example;

import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.example.config.AppConfig;
import org.example.config.ConfigFactory;

/** Builds the configuration and prints what was resolved and where each value came from. */
public final class App {

  private App() {}

  /**
   * Entry point.
   *
   * @param args configuration arguments, e.g. {@code --app.port=9000}
   */
  public static void main(final String[] args) {
    final SmallRyeConfig config;
    try {
      config = ConfigFactory.create(args);
    } catch (ConfigValidationException e) {
      // Every mapping problem is collected and reported together.
      System.err.println("Invalid configuration:");
      for (int i = 0; i < e.getProblemCount(); i++) {
        System.err.println("  - " + e.getProblem(i).getMessage());
      }
      System.exit(1);
      return;
    }

    final AppConfig app = config.getConfigMapping(AppConfig.class);

    printSources(config);
    printTyped(app);
    printRawApi(config);
    printProvenance(config);
  }

  private static void printSources(final SmallRyeConfig config) {
    System.out.println("== Config sources (highest priority first)");
    for (final ConfigSource s : config.getConfigSources()) {
      System.out.printf("  %11d  %s%n", s.getOrdinal(), s.getName());
    }
    System.out.println("  profiles: " + config.getProfiles());
  }

  /** The type-safe mapping: what application code should normally use. */
  private static void printTyped(final AppConfig app) {
    System.out.println("\n== Typed values from @ConfigMapping AppConfig");
    System.out.println("  name              (String)        " + app.name());
    System.out.println("  description       (Optional)      " + app.description());
    System.out.println("  environment       (@WithName env) " + app.environment());
    System.out.println("  port              (int)           " + app.port());
    System.out.println("  debug             (boolean)       " + app.debug());
    System.out.println("  sampleRate        (double)        " + app.sampleRate());
    System.out.println("  maxPayloadBytes   (long)          " + app.maxPayloadBytes());
    System.out.println("  workerThreads     (OptionalInt)   " + app.workerThreads());
    System.out.println("  logLevel          (enum)          " + app.logLevel());
    System.out.println("  requestTimeout    (Duration)      " + app.requestTimeout());
    System.out.println("  homepage          (Optional<URI>) " + app.homepage());
    System.out.println("  tags              (List<String>)  " + app.tags());
    System.out.println("  proxy             (HostPort)      " + app.proxy());
    System.out.println("  peers             (List<HostPort>)" + app.peers());

    final AppConfig.Server server = app.server();
    System.out.println("  server            (group)         " + server.host() + ":" + server.port());
    System.out.println("  server.tls        (Optional grp)  "
        + server.tls().map(t -> "keystore=" + t.keystore() + " password=****").orElse("<none>"));
    System.out.println("  tlsEnabled        (default meth)  " + app.tlsEnabled());

    System.out.println("  datasources       (Map<String,Datasource>)");
    app.datasources().forEach((name, ds) -> System.out.printf(
        "    %-10s url=%s user=%s pool=%d password=%s%n",
        name, ds.url(), ds.username(), ds.poolSize(), ds.password().map(p -> "****").orElse("-")));

    System.out.println("  endpoints         (List<Endpoint>)");
    for (int i = 0; i < app.endpoints().size(); i++) {
      final AppConfig.Endpoint ep = app.endpoints().get(i);
      System.out.printf("    [%d] %-8s %s retries=%d headers=%s%n",
          i, ep.name(), ep.url(), ep.retries(), ep.headers());
    }

    System.out.println("  labels            (Map<String,String>) " + app.labels());
    System.out.println("  plugins           (Map<String,@WithParentName Map>)");
    app.plugins().forEach((name, p) ->
        System.out.printf("    %-10s enabled=%s props=%s%n", name, p.enabled(), p.props()));
  }

  /** The untyped MicroProfile API: string keys looked up one at a time. */
  private static void printRawApi(final SmallRyeConfig config) {
    System.out.println("\n== Raw lookups via SmallRyeConfig");
    System.out.println("  getValue(app.port, Integer)            = "
        + config.getValue("app.port", Integer.class));
    System.out.println("  getOptionalValue(app.missing, String)  = "
        + config.getOptionalValue("app.missing", String.class));
    System.out.println("  getValues(app.tags, String)            = "
        + config.getValues("app.tags", String.class));
    System.out.println("  getValues(app.labels, String, String)  = "
        + config.getValues("app.labels", String.class, String.class));
  }

  /** Every app.* property with its winning source; useful when debugging overrides. */
  private static void printProvenance(final SmallRyeConfig config) {
    System.out.println("\n== Provenance of app.* properties");
    final TreeSet<String> names = new TreeSet<>();
    StreamSupport.stream(config.getPropertyNames().spliterator(), false)
        .filter(n -> n.startsWith("app."))
        .forEach(names::add);
    for (final String name : names) {
      final ConfigValue v = config.getConfigValue(name);
      // Skip unset keys, and list defaults shadowed by indexed keys such as app.tags[0].
      if (v.getValue() == null
          || (v.getSourceOrdinal() == Integer.MIN_VALUE && names.contains(name + "[0]"))) {
        continue;
      }
      System.out.printf("  %-36s = %-36s <- %s (%d)%n",
          name, mask(name, v.getValue()), shortName(v.getSourceName()), v.getSourceOrdinal());
    }
  }

  /** Trims "YamlConfigSource[source=file:/long/path/x.yaml]" to "YamlConfigSource[x.yaml]". */
  private static String shortName(final String sourceName) {
    return sourceName.replaceAll("\\[source=[^\\]]*/", "[");
  }

  private static String mask(final String name, final String value) {
    final String lower = name.toLowerCase(Locale.ROOT);
    final boolean secret = lower.contains("password") || lower.contains("secret")
        || lower.contains("token") || lower.contains("api-key");
    return secret ? "****" : Optional.ofNullable(value).orElse("");
  }
}
