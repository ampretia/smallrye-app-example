package org.example.config;

import io.smallrye.config.common.MapBackedConfigSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes command-line arguments as a config source.
 *
 * <p>Accepted forms:
 *
 * <ul>
 *   <li>{@code --app.port=9000} : key/value; everything after the first {@code =} is the value
 *   <li>{@code --app.debug} : bare flag, value {@code true}
 * </ul>
 *
 * <p>Anything else is kept as a positional argument and left for the application to use.
 *
 * <p>SmallRye has no built-in command-line source because argument syntax is application specific;
 * a {@link MapBackedConfigSource} with a high ordinal is all that is needed.
 */
public final class CommandLineConfigSource extends MapBackedConfigSource {

  /** Above system properties (400), so the command line always wins. */
  public static final int ORDINAL = 500;

  private static final long serialVersionUID = 1L;

  private final List<String> positional;

  /**
   * Parse arguments.
   *
   * @param args raw {@code main} arguments
   */
  public CommandLineConfigSource(final String... args) {
    this(new Parsed(args));
  }

  private CommandLineConfigSource(final Parsed parsed) {
    super("CommandLineConfigSource", parsed.properties, ORDINAL);
    this.positional = Collections.unmodifiableList(parsed.positional);
  }

  /** Arguments that were not recognised as configuration. */
  public List<String> positional() {
    return positional;
  }

  private static final class Parsed {
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    Parsed(final String... args) {
      for (final String arg : args) {
        if (arg.startsWith("--") && arg.length() > 2) {
          final String body = arg.substring(2);
          final int eq = body.indexOf('=');
          if (eq < 0) {
            properties.put(body, "true");
          } else {
            properties.put(body.substring(0, eq), body.substring(eq + 1));
          }
        } else {
          positional.add(arg);
        }
      }
    }
  }
}
