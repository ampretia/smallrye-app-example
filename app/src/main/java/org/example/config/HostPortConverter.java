package org.example.config;

import org.eclipse.microprofile.config.spi.Converter;

/**
 * Converts {@code "host:port"} into a {@link HostPort}.
 *
 * <p>Converters are plain classes: register them with {@code
 * SmallRyeConfigBuilder.withConverter(...)}, list them in {@code
 * META-INF/services/org.eclipse.microprofile.config.spi.Converter}, or attach one to a single
 * mapping method with {@code @WithConverter}.
 */
public final class HostPortConverter implements Converter<HostPort> {

  @Override
  public HostPort convert(final String value) {
    final String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      // Returning null tells SmallRye "no value", so an Optional<HostPort> becomes empty.
      return null;
    }
    final int colon = trimmed.lastIndexOf(':');
    if (colon <= 0 || colon == trimmed.length() - 1) {
      throw new IllegalArgumentException("Expected host:port but got '" + value + "'");
    }
    final int port;
    try {
      port = Integer.parseInt(trimmed.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Port is not a number in '" + value + "'", e);
    }
    return new HostPort(trimmed.substring(0, colon), port);
  }
}
