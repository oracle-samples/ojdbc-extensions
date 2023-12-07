package oracle.jdbc.provider.oci.configuration;

import com.oracle.bmc.databasetools.model.DatabaseToolsConnection;
import com.oracle.bmc.databasetools.model.DatabaseToolsConnectionOracleDatabase;
import com.oracle.bmc.databasetools.model.DatabaseToolsKeyStore;
import com.oracle.bmc.databasetools.model.DatabaseToolsKeyStoreContent;
import com.oracle.bmc.databasetools.model.DatabaseToolsKeyStoreContentSecretId;
import com.oracle.bmc.databasetools.model.DatabaseToolsKeyStorePassword;
import com.oracle.bmc.databasetools.model.DatabaseToolsKeyStorePasswordSecretId;
import com.oracle.bmc.databasetools.model.DatabaseToolsUserPassword;
import com.oracle.bmc.databasetools.model.DatabaseToolsUserPasswordSecretId;
import com.oracle.bmc.databasetools.model.LifecycleState;
import com.oracle.bmc.databasetools.model.DatabaseToolsConnectionOracleDatabaseProxyClient;
import com.oracle.bmc.databasetools.model.DatabaseToolsConnectionOracleDatabaseProxyClientUserName;
import com.oracle.bmc.model.BmcException;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.provider.oci.databasetools.DatabaseToolsConnectionFactory;
import oracle.jdbc.provider.oci.vault.Secret;
import oracle.jdbc.provider.oci.vault.SecretFactory;
import oracle.jdbc.provider.parameter.ParameterSet;
import oracle.jdbc.spi.OracleConfigurationCachableProvider;
import oracle.jdbc.spi.OracleConfigurationProvider;
import oracle.jdbc.util.OracleConfigurationCache;
import oracle.jdbc.util.OracleConfigurationProviderNetworkError;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class OciDatabaseToolsConnectionProvider
    implements OracleConfigurationCachableProvider {

  private static final String CONFIG_TIME_TO_LIVE =
    "config_time_to_live";
  private static final long MS_TIMEOUT = 60_000L;
  private static final long MS_REFRESH_INTERVAL = 60_000L;

  private final OracleConfigurationCache cache = OracleConfigurationCache
    .create(100);

  private ParameterSet commonParameters;

  @Override
  public Properties getConnectionProperties(String location)
      throws SQLException {

    Properties cachedProp = cache.get(location);
    if (Objects.nonNull(cachedProp)) {
      return cachedProp;
    }

    // Retrieve and add the properties to the cache
    Properties properties = getRemoteProperties(location);

    if (properties.containsKey(CONFIG_TIME_TO_LIVE)) {
      long configTimeToLive = Long.parseLong(
        properties.getProperty(CONFIG_TIME_TO_LIVE));

      // properties stored in the cache should not contain information of TTL
      properties.remove(CONFIG_TIME_TO_LIVE);
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

  @Override
  public String getType() {
    return "ocidbtools";
  }

  private Properties getRemoteProperties(String location) {
    // Split connection ocid from options
    Map<String, String> options = null;
    String[] params = location.split("\\?");
    if (params.length > 1) {
      options = OracleConfigurationProvider.mapOptions(params[1]);
    } else {
      options = new HashMap<>();
    }

    commonParameters = OciConfigurationParameters.getParser()
      .parseNamedValues(options)
      .copyBuilder()
      .add("connection_ocid",
        DatabaseToolsConnectionFactory.CONNECTION_OCID,
        params[0])
      .build();

    DatabaseToolsConnection dbToolsConnection =
      DatabaseToolsConnectionFactory.getInstance()
        .request(commonParameters)
        .getContent();

    /* check Type is Oracle Database */
    if(!(dbToolsConnection instanceof DatabaseToolsConnectionOracleDatabase))
      throw new IllegalStateException(
        "Unsupported class type: " + dbToolsConnection.getClass().getTypeName());
    DatabaseToolsConnectionOracleDatabase connection =
      (DatabaseToolsConnectionOracleDatabase) dbToolsConnection;

    /* check state is valid */
    LifecycleState state = connection.getLifecycleState();
    if (!(state.equals(LifecycleState.Active) || state.equals(
      LifecycleState.Updating))) {
      throw new IllegalStateException(
        "Connection requested is in invalid state. Only ACTIVE or UPDATING " +
          "are valid. Current state: " + state);
    }

    Properties properties = new Properties();
    properties.put("URL",
      "jdbc:oracle:thin:@" + connection.getConnectionString());
    String username = connection.getUserName();
    if (username != null)
      properties.put("user", username);

    /* Get password from Secret */
    DatabaseToolsUserPassword dbToolsUserPassword = connection.getUserPassword();
    if (dbToolsUserPassword != null)
      properties.put(
        "password",
        String.valueOf(getSecret(dbToolsUserPassword).toCharArray()));

    /* Get properties that are associated with Wallet */
    if (connection.getKeyStores() != null) {
      Properties walletProps = new Properties();

      for (DatabaseToolsKeyStore keyStore : connection.getKeyStores()) {
        // Get the base64 content of the Wallet, which has a format of KeyStore,
        // TrustStore, PKCS12, or SSO
        String base64KeyStoreContent =
          getSecret(keyStore.getKeyStoreContent())
            .getBase64Secret();

        DatabaseToolsKeyStorePassword keyStorePassword =
          keyStore.getKeyStorePassword();

        switch (keyStore.getKeyStoreType()) {
        case JavaKeyStore:
          walletProps.setProperty(
            OracleConnection.CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTORETYPE,
            "JKS");
          walletProps.setProperty(
            OracleConnection.CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTORE,
            "data:;base64," + base64KeyStoreContent);
          walletProps.setProperty(
            OracleConnection
              .CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTOREPASSWORD,
            String.valueOf(getSecret(keyStorePassword).toCharArray()));
          break;
        case JavaTrustStore:
          walletProps.setProperty(
            OracleConnection
              .CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_TRUSTSTORETYPE,
            "JKS");
          walletProps.setProperty(
            OracleConnection.CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_TRUSTSTORE,
            "data:;base64," + base64KeyStoreContent);
          walletProps.setProperty(
            OracleConnection
              .CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_TRUSTSTOREPASSWORD,
            String.valueOf(getSecret(keyStorePassword).toCharArray()));
          break;
        case Pkcs12:
        case Sso:
          walletProps.put(
            OracleConnection.CONNECTION_PROPERTY_WALLET_LOCATION,
            "data:;base64," + base64KeyStoreContent);
          break;
        case UnknownEnumValue:
        default:
          throw new IllegalStateException(
            "Unknown keyStore type: " + keyStore.getKeyStoreType());
        }
      }

      // Add all the wallet-associated properties
      properties.putAll(walletProps);
    }

    Map<String, String> advancedProps = connection.getAdvancedProperties();
    if (advancedProps != null)
      properties.putAll(connection.getAdvancedProperties());

    /* check if database tools connection has proxy client info */
    DatabaseToolsConnectionOracleDatabaseProxyClient proxyClient =
      connection.getProxyClient();

    if (proxyClient instanceof DatabaseToolsConnectionOracleDatabaseProxyClientUserName) {
      DatabaseToolsConnectionOracleDatabaseProxyClientUserName proxyClientUserName =
        (DatabaseToolsConnectionOracleDatabaseProxyClientUserName) proxyClient;

      /* check if proxyClient has password or roles */
      if (proxyClientUserName.getUserPassword() != null || proxyClientUserName.getRoles() != null) {
        throw new UnsupportedOperationException(
          "Unsupported feature: the proxyClient of this database tools " +
            "connection has user password or roles");
      }
      properties.put(OracleConnection.CONNECTION_PROPERTY_PROXY_CLIENT_NAME, proxyClientUserName.getUserName());
    }

    return properties;
  }

  /**
   * Returns a {@code Secret} from the given {@code userPassword}.
   * @param userPassword the user password of a Database Tools Connection
   * @return a {@code Secret} managed by the OCI Vault service
   */
  private Secret getSecret(DatabaseToolsUserPassword userPassword) {
    /* check the value type of Database Tools user password is SECRETID */
    if (!(userPassword instanceof DatabaseToolsUserPasswordSecretId))
      throw new IllegalStateException(
        "Unsupported class type: " + userPassword.getClass().getTypeName());

    String secretId = ((DatabaseToolsUserPasswordSecretId) userPassword)
      .getSecretId();

    return requestSecretFromOCI(secretId);
  }

  /**
   * Returns a {@code Secret} from the given {@code keyStoreContent}.
   * @param keyStoreContent the content of a key store of Database Tools
   *                        Connection
   * @return a {@code Secret} managed by the OCI Vault service
   */
  private Secret getSecret(
    DatabaseToolsKeyStoreContent keyStoreContent) {
    /* check the value type of Database Tools Key Store content is SECRETID */
    if (!(keyStoreContent instanceof DatabaseToolsKeyStoreContentSecretId))
      throw new IllegalStateException(
        "Unsupported class type: " + keyStoreContent.getClass().getTypeName());

      String secretId =
        ((DatabaseToolsKeyStoreContentSecretId) keyStoreContent)
          .getSecretId();

    return requestSecretFromOCI(secretId);
  }

  /**
   * Returns a {@code Secret} from the given {@code keyStorePassword}.
   * @param keyStorePassword the password of a key store of Database Tools
   *                         Connection
   * @return a {@code Secret} managed by the OCI Vault service
   */
  private Secret getSecret(
    DatabaseToolsKeyStorePassword keyStorePassword) {
    /* check the value type of Database Tools Key Store password is SECRETID */
    if (!(keyStorePassword instanceof DatabaseToolsKeyStorePasswordSecretId))
      throw new IllegalStateException(
        "Unsupported class type: " + keyStorePassword.getClass().getTypeName());

    String secretId =
      ((DatabaseToolsKeyStorePasswordSecretId) keyStorePassword)
        .getSecretId();

    return requestSecretFromOCI(secretId);
  }

  /**
   * Requests a {@code Secret} from OCI using the given {@code secretId} and the
   * common parameters that is already configured.
   * @param secretId the Secret OCID
   * @return a {@code Secret} managed by the OCI Vault service
   */
  private Secret requestSecretFromOCI(String secretId) {
    ParameterSet walletParameters = commonParameters.copyBuilder()
      .add("value", SecretFactory.OCID, secretId)
      .build();

    return SecretFactory.getInstance()
      .request(walletParameters)
      .getContent();
  }

  @Override
  public Properties removeProperties(String location) {
    Properties deletedProp = cache.remove(location);
    return deletedProp;
  }

  private Properties refreshProperties(String location)
    throws OracleConfigurationProviderNetworkError {
    try {
      return getRemoteProperties(location);
    } catch (BmcException bmcException) {
      throw new OracleConfigurationProviderNetworkError(bmcException);
    }
  }
}

