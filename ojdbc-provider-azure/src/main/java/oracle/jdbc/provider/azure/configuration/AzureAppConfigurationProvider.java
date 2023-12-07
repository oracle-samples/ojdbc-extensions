/*
 ** Copyright (c) 2023 Oracle and/or its affiliates.
 **
 ** The Universal Permissive License (UPL), Version 1.0
 **
 ** Subject to the condition set forth below, permission is hereby granted to any
 ** person obtaining a copy of this software, associated documentation and/or data
 ** (collectively the "Software"), free of charge and under any and all copyright
 ** rights in the Software, and any and all patent rights owned or freely
 ** licensable by each licensor hereunder covering either (i) the unmodified
 ** Software as contributed to or provided by such licensor, or (ii) the Larger
 ** Works (as defined below), to deal in both
 **
 ** (a) the Software, and
 ** (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 ** one is included with the Software (each a "Larger Work" to which the Software
 ** is contributed by such licensors),
 **
 ** without restriction, including without limitation the rights to copy, create
 ** derivative works of, display, perform, and distribute the Software and make,
 ** use, sell, offer for sale, import, export, have made, and have sold the
 ** Software and the Larger Work(s), and to sublicense the foregoing rights on
 ** either these or other terms.
 **
 ** This license is subject to the following condition:
 ** The above copyright notice and either this complete permission notice or at
 ** a minimum a reference to the UPL must be included in all copies or
 ** substantial portions of the Software.
 **
 ** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 ** IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 ** FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 ** AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 ** LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 ** OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 ** SOFTWARE.
 */

package oracle.jdbc.provider.azure.configuration;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.AzureException;
import com.azure.core.util.UrlBuilder;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.data.appconfiguration.models.SettingSelector;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.spi.OracleConfigurationCachableProvider;
import oracle.jdbc.util.OracleConfigurationCache;
import oracle.jdbc.util.OracleConfigurationProviderNetworkError;
import oracle.jdbc.spi.OracleConfigurationProvider;
import oracle.jdbc.provider.parameter.ParameterSet;
import oracle.jdbc.provider.azure.authentication.TokenCredentialFactory;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * <p>
 *   A provider of App Configuration values from Azure.
 * </p>
 */
public class AzureAppConfigurationProvider
  implements OracleConfigurationCachableProvider {
  private static final OracleJsonFactory JSON_FACTORY = new OracleJsonFactory();

  private static final String CONNECT_DESCRIPTOR_PROPERTIES_NAME =
      "connect_descriptor";
  private static final String WALLET_LOCATION_PROPERTIES_NAME =
    "wallet_location";
  private static final String JDBC_PROPERTIES_PREFIX = "jdbc/";
  private static final String CONFIG_TTL_JSON_OBJECT_NAME =
    "config_time_to_live";

  private static final long MS_TIMEOUT = 60_000L;
  private static final long MS_REFRESH_INTERVAL = 60_000L;
  private final OracleConfigurationCache cache = OracleConfigurationCache
    .create(100);

  /**
   * {@inheritDoc}
   * <p>
   * Returns the connection properties configured in Azure App Configuration.
   * </p><p>
   * The {@code value} is a fragment section of the URL processed by the JDBC
   * driver, which has a format of:
   * </p><pre>{@code
   *   {appconfig-name}[?key=prefix&label=value&option1=value1&option2=value2...]
   * }</pre>
   *
   * @param location the value used by this provider to retrieve configuration(s)
   *              from Azure
   * @return connection properties that are configured in Azure App
   *         Configuration
   */
  @Override
  public Properties getConnectionProperties(String location) {
    // If the location was already consulted, re-use the properties
    Properties cachedProp = cache.get(location);
    if (Objects.nonNull(cachedProp)) {
      return cachedProp;
    }

    // Retrieve and add the properties to the cache
    Properties properties = getRemoteProperties(location);
    if (properties.containsKey(CONFIG_TTL_JSON_OBJECT_NAME)) {
      long configTimeToLive = Long.parseLong(
        properties.getProperty(CONFIG_TTL_JSON_OBJECT_NAME));

      // properties stored in the cache should not contain information of TTL
      properties.remove(CONFIG_TTL_JSON_OBJECT_NAME);
      cache.put(
        location,
        properties,
        configTimeToLive,
        () -> this.refreshProperties(location),
        MS_TIMEOUT,
        MS_REFRESH_INTERVAL);
    } else {
      cache.put(location,
        properties,
        () -> this.refreshProperties(location),
        MS_TIMEOUT,
        MS_REFRESH_INTERVAL);
    }

    return properties;
  }

  /**
   * {@inheritDoc}
   * Returns type of this provider, which is a unique identifier for the
   * Service Provider Interface.
   *
   * @return type of this provider
   */
  @Override
  public String getType() {
    return "azure";
  }

  private Properties getRemoteProperties(String location) {
    AzureAppConfigurationURLParser appConfig =
      new AzureAppConfigurationURLParser(location);
    ParameterSet parameters = appConfig.getParameters();
    TokenCredential credential =
      TokenCredentialFactory.getInstance()
        .request(parameters)
        .getContent();

    String prefix = appConfig.getPrefix();

    // Filter to retrieve the keys, user provides separator, if any
    SettingSelector selector = new SettingSelector();
    if (prefix != null) {
      // Add the wildcard to the end of the prefix
      selector.setKeyFilter(prefix + "*");
    }

    if (parameters.contains(AzureAppConfigurationURLParser.LABEL)) {
      selector.setLabelFilter(
        parameters.getOptional(AzureAppConfigurationURLParser.LABEL));
    }

    ConfigurationClient configurationClient = new ConfigurationClientBuilder()
      .credential(credential)
      .endpoint("https://" + appConfig.getName() + ".azconfig.io")
      .buildClient();

    Properties properties = new Properties();
    for (ConfigurationSetting config : configurationClient
      .listConfigurationSettings(selector)) {

      String key = config.getKey();
      // Remove prefix from key
      if (prefix != null && key.startsWith(prefix))
        key = key.substring(prefix.length());

      // Remove 'jdbc/' to use reflection from OracleConnection keys
      if (key.startsWith(JDBC_PROPERTIES_PREFIX)) {
        key = key.substring(JDBC_PROPERTIES_PREFIX.length());
      }

      // The value is either a String or a key reference
      String value =
          config.getContentType() == null || config.getContentType().isEmpty()
              ? config.getValue()
              : getSecretValue(credential, config.getValue());

      // User common connect_descriptor as the URL
      if (key.startsWith(CONNECT_DESCRIPTOR_PROPERTIES_NAME)) {
        key = "URL";
        value = "jdbc:oracle:thin:@" + value;
      }

      // wallet_location
      if (key.startsWith(WALLET_LOCATION_PROPERTIES_NAME)) {
        key = OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION;
        value = "data:;base64," + value;
      }

      properties.put(key, value);
    }

    // Check mandatory attributes
    if (!properties.containsKey("URL")) {
      throw new IllegalArgumentException("Missing mandatory attributes: " +
        CONNECT_DESCRIPTOR_PROPERTIES_NAME);
    }

    return properties;
  }

  /**
   * Returns the value of a Secret from Azure Key Vault.
   * @param credential the credential to use for authenticating the Azure
   *                   request
   * @param secretReference reference to the Secret which has a format of {@code
   *        {"uri":"https://mykeyvault.vault.azure.net/secrets/mySecret"}}
   * @return the value of the Secret
   */
  private String getSecretValue(TokenCredential credential,
                                String secretReference) {
    InputStream secretRefStream = new ByteArrayInputStream(
      secretReference.getBytes());
    OracleJsonObject json = JSON_FACTORY
        .createJsonTextValue(secretRefStream)
        .asJsonObject();
    String vaultUrlAndName = json
        .get("uri")
        .asJsonString()
        .getString();

    UrlBuilder urlBuilder = UrlBuilder.parse(vaultUrlAndName);

    String vaultUrl = "https://" + urlBuilder
        .getHost();
    String name = urlBuilder
        .getPath()
        .replace("/secrets", "");

    SecretClient secretClient = new SecretClientBuilder()
        .vaultUrl(vaultUrl)
        .credential(credential)
        .buildClient();
    return secretClient.getSecret(name).getValue();
  }

  private Properties refreshProperties(String location)
    throws OracleConfigurationProviderNetworkError {
      try {
        return getRemoteProperties(location);
      } catch (AzureException e) {
        throw new OracleConfigurationProviderNetworkError(e);
      }
  }

  @Override
  public Properties removeProperties(String location) {
    Properties deletedProp = cache.remove(location);
    return deletedProp;
  }
}


