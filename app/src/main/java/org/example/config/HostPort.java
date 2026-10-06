package org.example.config;

/**
 * A value type with no String constructor or parse method, so SmallRye needs an explicit {@link
 * HostPortConverter} to build it from configuration.
 *
 * @param host host name or address
 * @param port TCP port
 */
public record HostPort(String host, int port) {

  @Override
  public String toString() {
    return host + ":" + port;
  }
}
