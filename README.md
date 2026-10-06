# SmallRye Config standalone example

This is a plain Java `main()` application. It uses [SmallRye Config](https://smallrye.io/smallrye-config/)
to merge configuration from files, environment variables, system properties and command-line
arguments into one type-safe object. It runs without CDI, Jakarta EE, Quarkus or an
application server.

```bash
./gradlew test                       # 25 tests covering every source and type
./gradlew run                        # print resolved config + where each value came from
./gradlew run --args="--app.port=9000 --smallrye.config.profile=dev"
```

## Layout

| File                                                                                                                                                            | Purpose                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| [`AppConfig.java`](app/src/main/java/org/example/config/AppConfig.java)                                                                                         | `@ConfigMapping` interface: every supported type      |
| [`ConfigFactory.java`](app/src/main/java/org/example/config/ConfigFactory.java)                                                                                 | Builds `SmallRyeConfig` by hand; defines source order |
| [`CommandLineConfigSource.java`](app/src/main/java/org/example/config/CommandLineConfigSource.java)                                                             | Turns `--key=value` args into a config source         |
| [`HostPort.java`](app/src/main/java/org/example/config/HostPort.java) / [`HostPortConverter.java`](app/src/main/java/org/example/config/HostPortConverter.java) | Custom value type + `Converter`                       |
| [`application.yaml`](app/src/main/resources/application.yaml)                                                                                                   | Packaged defaults and a `%dev` profile                |
| [`config/prod.yaml`](config/prod.yaml), [`config/override.properties`](config/override.properties)                                                              | Example external files                                |
| [`App.java`](app/src/main/java/org/example/App.java)                                                                                                            | Prints typed values, raw lookups and provenance       |

## How it works without a container

SmallRye Config is a library. In Quarkus or a MicroProfile server the runtime builds the config
and injects `@ConfigProperty` / `@ConfigMapping` beans. Standalone, you do those steps yourself:

```java
SmallRyeConfig config = new SmallRyeConfigBuilder()
    .addDefaultInterceptors()                        // ${expr}, %profile, relocations
    .withSources(cli, sysProps, env, file, classpath) // explicit, ordered by ordinal
    .withConverter(HostPort.class, 100, new HostPortConverter())
    .withMapping(AppConfig.class)                    // generate + validate the interface
    .build();

AppConfig app = config.getConfigMapping(AppConfig.class);
```

`build()` generates the implementation of `AppConfig` and validates it eagerly. A missing required
value, a bad conversion, or an unknown `app.*` key (such as a typo) fails startup, and every
problem is listed at once:

```console
$ app --app.port=abc --app.proxy=nocolon --app.server.tls.keystore=/k --app.nmae=typo
Invalid configuration:
  - app.port with the config value "abc": Expected an integer value, got "abc"
  - app.proxy with the config value "nocolon": Expected host:port but got 'nocolon'
  - app.server.tls.password is required but it could not be found in any config source
  - SRCFG00050: app.nmae in CommandLineConfigSource does not map to any root
```

`ConfigFactory.create(args, env, sysProps)` takes the environment as parameters instead of reading
it globally, so tests can build any scenario without changing process state.

## Sources and priority

Values are resolved **per key**. A higher ordinal hides the same key in lower sources, while all
other keys still come from below. Sources merge; they do not replace each other.

| Ordinal | Source                              | Example                                                       |
| ------- | ----------------------------------- | ------------------------------------------------------------- |
| 500     | Command line                        | `--app.port=9000`, `--app.debug` (bare flag = `true`)         |
| 400     | System properties                   | `-Dapp.port=9000` (via `JAVA_OPTS`)                           |
| 300     | Environment                         | `APP_PORT=9000`                                               |
| 275     | External file (YAML or .properties) | `--app.config-file=config/prod.yaml` or `APP_CONFIG_FILE=...` |
| 250     | Classpath `application.yaml`        | packaged defaults                                             |
| min     | `@WithDefault` in `AppConfig`       | code defaults                                                 |

The external file location is itself configuration. `ConfigFactory` builds a small bootstrap config
from CLI, system properties and env to find it, then builds the real config. (SmallRye also has a
built-in `smallrye.config.locations` property for this. The explicit version here makes the
ordinal visible.)

### Environment variable names

SmallRye maps env vars back to property names. Every character that is not a letter or digit
becomes `_`, and the result is upper-cased:

| Property                           | Env var                                |
| ---------------------------------- | -------------------------------------- |
| `app.port`                         | `APP_PORT`                             |
| `app.sample-rate`                  | `APP_SAMPLE_RATE`                      |
| `app.server.tls.password`          | `APP_SERVER_TLS_PASSWORD`              |
| `app.datasources.orders.pool-size` | `APP_DATASOURCES_ORDERS_POOL_SIZE`     |
| `app.endpoints[1].retries`         | `APP_ENDPOINTS_1__RETRIES` (note `__`) |
| `smallrye.config.profile`          | `SMALLRYE_CONFIG_PROFILE`              |

Because `@ConfigMapping` knows the structure, env vars can **add** new map entries
(`APP_DATASOURCES_REPORTING_URL` creates a `reporting` datasource), not only override existing ones.

## Representing different kinds of values

All of these are in `AppConfig.java` and covered by `ConfigFactoryTest`:

### Basic Types & Defaults

| Kind                | Declaration                                                                      | Config                |
| ------------------- | -------------------------------------------------------------------------------- | --------------------- |
| String (required)   | `String name()`                                                                  | `app.name=x`          |
| String (optional)   | `Optional<String> description()`                                                 | may be absent         |
| Renamed key         | `@WithName("env") String environment()`                                          | `app.env=prod`        |
| Primitives          | `int port()`, `boolean debug()`, `double sampleRate()`, `long maxPayloadBytes()` | `app.sample-rate=0.5` |
| Optional primitive  | `OptionalInt workerThreads()`                                                    | may be absent         |
| Defaults            | `@WithDefault("8080")`                                                           | fallback if omitted   |
| Enum                | `LogLevel logLevel()`                                                            | `warn` / `WARN`       |
| Built-in conversion | `Duration`, `URI` (`parse`/`of`/`valueOf` or `String` constructor)               | `PT30S`               |
| Custom type         | `Optional<HostPort> proxy()` + `HostPortConverter`                               | `proxy.corp:3128`     |
| Derived value       | `default boolean tlsEnabled()`                                                   | computed              |

### Collections, Maps & Groups

| Kind                              | Declaration                             | Config                                          |
| --------------------------------- | --------------------------------------- | ----------------------------------------------- |
| List of values                    | `List<String> tags()`                   | `a,b,c` **or** `tags[0]=a` **or** YAML sequence |
| List of custom type               | `List<HostPort> peers()`                | `a:1,b:2`                                       |
| Nested group                      | `Server server()`                       | `app.server.host`                               |
| Optional group                    | `Optional<Tls> tls()`                   | present if any `tls.*` set                      |
| Map of groups                     | `Map<String, Datasource> datasources()` | `app.datasources.<name>.url`                    |
| List of groups                    | `List<Endpoint> endpoints()`            | `app.endpoints[0].url`                          |
| Free-form map                     | `Map<String,String> labels()`           | `app.labels.<anything>=v`                       |
| Untyped group (`@WithParentName`) | `Map<String, Plugin> plugins()`         | `app.plugins.<name>.<anything>=v`               |

Other features shown:

- **Expressions:** `description: "${app.name} (${app.env}) ..."`, `${KEYSTORE_PASSWORD:changeit}`
- **Profiles:** `"%dev": { app: { debug: true } }` in YAML (`%dev.app.debug=true` in properties),
  activated with `--smallrye.config.profile=dev` or `SMALLRYE_CONFIG_PROFILE=dev`
- **Provenance:** `config.getConfigValue(name).getSourceName()` reports which source supplied each
  value. `App` prints this for every `app.*` key.
- **Raw API:** `getValue`, `getOptionalValue`, `getValues(name, K, V)` for code that looks keys up by name

## Try it

Build the application distribution binary first:

```bash
./gradlew installDist
```

Executable binary path: `./app/build/install/app/bin/app`

### 1. Default packaged config

```console
$ ./app/build/install/app/bin/app
== Config sources (highest priority first)
          500  CommandLineConfigSource
          400  PropertiesConfigSource[source=SysPropConfigSource]
          300  EnvConfigSource
          250  YamlConfigSource[source=jar:.../app.jar!/application.yaml]
  -2147483648  DefaultValuesConfigSource

== Typed values from @ConfigMapping AppConfig
  name              (String)        smallrye-example
  description       (Optional)      Optional[smallrye-example (local) listening on 0.0.0.0:8443]
  port              (int)           8080
  logLevel          (enum)          INFO
  peers             (List<HostPort>)[node-a:7000, node-b:7000]
  server.tls        (Optional grp)  <none>

== Provenance of app.* properties
  app.port                             = 8080                                 <- YamlConfigSource[application.yaml] (250)
  app.env                              = local                                <- DefaultValuesConfigSource (-2147483648)
```
*Note: Uses packaged `application.yaml` defaults (250) and code-level `@WithDefault` values.*

---

### 2. Command-line overrides

CLI arguments (ordinal 500) take highest priority and override matching keys:

```console
$ ./app/build/install/app/bin/app --app.port=9000 --app.log-level=error --app.peers=x:1,y:2
== Typed values from @ConfigMapping AppConfig
  port              (int)           9000
  logLevel          (enum)          ERROR
  peers             (List<HostPort>)[x:1, y:2]

== Provenance of app.* properties
  app.log-level                        = error                                <- CommandLineConfigSource (500)
  app.peers                            = x:1,y:2                              <- CommandLineConfigSource (500)
  app.port                             = 9000                                 <- CommandLineConfigSource (500)
```

---

### 3. System properties vs environment variables

System properties (`JAVA_OPTS`, ordinal 400) beat environment variables (ordinal 300):

```console
$ APP_PORT=7000 JAVA_OPTS=-Dapp.port=7500 ./app/build/install/app/bin/app
== Typed values from @ConfigMapping AppConfig
  port              (int)           7500

== Provenance of app.* properties
  app.port                             = 7500                                 <- PropertiesConfigSource[source=SysPropConfigSource] (400)
```

---

### 4. External YAML file & expression expansion

Loads an external configuration file (ordinal 275) and expands expression variables (e.g. `${KEYSTORE_PASSWORD}`):

```console
$ KEYSTORE_PASSWORD=s3cret ./app/build/install/app/bin/app --app.config-file=config/prod.yaml
== Typed values from @ConfigMapping AppConfig
  description       (Optional)      Optional[smallrye-example (prod) listening on 0.0.0.0:8443]
  environment       (@WithName env) prod
  port              (int)           80
  logLevel          (enum)          WARN
  proxy             (HostPort)      Optional[proxy.corp:3128]
  server.tls        (Optional grp)  keystore=/etc/app/keystore.p12 password=****
  tlsEnabled        (default meth)  true
  workerThreads     (OptionalInt)   OptionalInt[16]

== Provenance of app.* properties
  app.config-file                      = config/prod.yaml                     <- CommandLineConfigSource (500)
  app.env                              = prod                                 <- YamlConfigSource[prod.yaml] (275)
  app.port                             = 80                                   <- YamlConfigSource[prod.yaml] (275)
  app.server.tls.password              = ****                                 <- YamlConfigSource[prod.yaml] (275)
```

---

### 5. Profile activation (`%dev`)

Activates the dev profile (`%dev`), overriding base defaults with profile values:

```console
$ ./app/build/install/app/bin/app --smallrye.config.profile=dev
== Config sources (highest priority first)
  profiles: [dev]

== Typed values from @ConfigMapping AppConfig
  description       (Optional)      Optional[smallrye-example (dev) listening on localhost:8443]
  environment       (@WithName env) dev
  debug             (boolean)       true
  logLevel          (enum)          DEBUG
  server            (group)         localhost:8443

== Provenance of app.* properties
  app.debug                            = true                                 <- YamlConfigSource[application.yaml] (250)
  app.env                              = dev                                  <- YamlConfigSource[application.yaml] (250)
  app.log-level                        = debug                                <- YamlConfigSource[application.yaml] (250)
  app.server.host                      = localhost                            <- YamlConfigSource[application.yaml] (250)
```

---

### 6. Dynamic environment variable mapping & collection indexing

Environment variables dynamically insert new map entries and index into lists (using `__` syntax):

```console
$ APP_DATASOURCES_REPORTING_URL=jdbc:x://r APP_ENDPOINTS_1__RETRIES=42 ./app/build/install/app/bin/app
== Typed values from @ConfigMapping AppConfig
  datasources       (Map<String,Datasource>)
    orders     url=jdbc:postgresql://db:5432/orders user=sa pool=20 password=-
    customers  url=jdbc:postgresql://db:5432/customers user=reader pool=10 password=-
    reporting  url=jdbc:x://r user=sa pool=10 password=-
  endpoints         (List<Endpoint>)
    [0] billing  https://billing.internal/api retries=3 headers={Accept=application/json}
    [1] audit    https://audit.internal/api retries=42 headers={}

== Provenance of app.* properties
  app.datasources.reporting.url        = jdbc:x://r                           <- EnvConfigSource (300)
  app.endpoints[1].retries             = 42                                   <- EnvConfigSource (300)
```
