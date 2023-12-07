package oracle.jdbc.provider.azure.configuration;

import com.azure.core.management.AzureEnvironment;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.resources.models.GenericResource;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import oracle.jdbc.pool.OracleDataSource;
import oracle.jdbc.provider.TestProperties;
import oracle.jdbc.provider.azure.AzureTestProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.azure.resourcemanager.resources.ResourceManager;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.core.management.profile.AzureProfile;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

//import static oracle.jdbc.provider.azure.configuration
// .AzureAppConfigurationProviderURLParserTest.getSecretCredentialClient;
import static org.junit.jupiter.api.Assertions.*;

class AzureAppConfigurationProviderTest {

  /**
   * Verify that the cache is purged after hitting 1017 error.
   * Specifically, get connection to the same url twice, but modify the 'user'
   * every time.
   * The provided app configuration should have correct username, password and
   * correct connect descriptor to connect to Database.
   * Note: Environment variables AZURE_CLIENT_ID, AZURE_TENANT_ID,
   * AZURE_CLIENT_SECRET should be set to run this test
   **/
  @Test
  public void testCachePurged() throws SQLException {
    ConfigurationClient client = getSecretCredentialClient();
    String APP_CONFIG_NAME=
      TestProperties.getOrAbort(AzureTestProperty.AZURE_APP_CONFIG_NAME);
    String APP_CONFIG_KEY =
      TestProperties.getOrAbort(AzureTestProperty.AZURE_APP_CONFIG_KEY);
    String APP_CONFIG_LABEL =
      TestProperties.getOrAbort(AzureTestProperty.AZURE_APP_CONFIG_LABEL);
    String username = "user";
    String url = "jdbc:oracle:thin:@config-azure:" + APP_CONFIG_NAME + "?key" +
      "=" + APP_CONFIG_KEY + "&label=" + APP_CONFIG_LABEL;

    String originalKeyValue =
      client.getConfigurationSetting(APP_CONFIG_KEY + username,
      APP_CONFIG_LABEL).getValue(); /* Retrieve original value of 'user' */

    /* Set value of 'user' wrong */
    client.setConfigurationSetting( APP_CONFIG_KEY + username,
      APP_CONFIG_LABEL, originalKeyValue + "wrong");

    /* Connection fails: hit 1017 */
    SQLException exception = assertThrows(SQLException.class,
      () -> tryConnection(url), "Should throw an SQLException");
    Assertions.assertEquals(exception.getErrorCode(), 1017);

    /* Set value of 'user' correct */
    ConfigurationSetting result =
      client.setConfigurationSetting(APP_CONFIG_KEY + username,
      APP_CONFIG_LABEL, originalKeyValue);
    Assertions.assertEquals(originalKeyValue, result.getValue());

    /* Connection succeeds */
    try (Connection conn = tryConnection(url)) {
      Assertions.assertNotNull(conn);

      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery("SELECT 'Hello, db' FROM sys.dual");
      Assertions.assertNotNull(rs.next());
      Assertions.assertEquals("Hello, db", rs.getString(1));
    }
  }

  /**
   * Helper function: try to get connection form specified url
   **/
  private Connection tryConnection(String url) throws SQLException {
    OracleDataSource ds = new OracleDataSource();
    ds.setURL(url);
    Connection conn = ds.getConnection();
    return conn;
  }

  /**
   * Similar to the method in AzureAppConfigurationProviderURLParserTest
   **/
  private static ConfigurationClient getSecretCredentialClient() {
    return new ConfigurationClientBuilder()
      .credential( new ClientSecretCredentialBuilder()
        .clientId(TestProperties.getOrAbort(AzureTestProperty.AZURE_CLIENT_ID))
        .clientSecret(
          TestProperties.getOrAbort(AzureTestProperty.AZURE_CLIENT_SECRET))
        .tenantId(TestProperties.getOrAbort(AzureTestProperty.AZURE_TENANT_ID))
        .build())
      .endpoint("https://" + TestProperties.getOrAbort(
        AzureTestProperty.AZURE_APP_CONFIG_NAME) + ".azconfig.io")
      .buildClient();
  }
}