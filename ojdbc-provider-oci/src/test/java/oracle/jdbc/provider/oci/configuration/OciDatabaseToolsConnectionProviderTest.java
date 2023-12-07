package oracle.jdbc.provider.oci.configuration;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.database.DatabaseClient;
import com.oracle.bmc.database.requests.GetAutonomousDatabaseRequest;
import com.oracle.bmc.database.responses.GetAutonomousDatabaseResponse;
import com.oracle.bmc.databasetools.DatabaseToolsClient;
import com.oracle.bmc.databasetools.model.*;
import com.oracle.bmc.databasetools.requests.CreateDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.requests.DeleteDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.requests.GetDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.requests.UpdateDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.responses.CreateDatabaseToolsConnectionResponse;
import com.oracle.bmc.databasetools.responses.DeleteDatabaseToolsConnectionResponse;
import com.oracle.bmc.databasetools.responses.GetDatabaseToolsConnectionResponse;
import com.oracle.bmc.databasetools.responses.UpdateDatabaseToolsConnectionResponse;
import oracle.jdbc.pool.OracleDataSource;
import oracle.jdbc.provider.TestProperties;
import oracle.jdbc.provider.oci.OciTestProperty;
import oracle.jdbc.spi.OracleConfigurationProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link OciDatabaseToolsConnectionProvider} as implementing
 * behavior specified by its JavaDoc.
 */

public class OciDatabaseToolsConnectionProviderTest {
  private static final OracleConfigurationProvider PROVIDER =
      OracleConfigurationProvider.find("ocidbtools");

  private static DatabaseToolsClient client;
  private static DatabaseClient dbClient;

  static {
    try {
      ConfigFileReader.ConfigFile configFile = ConfigFileReader.parseDefault();
      AuthenticationDetailsProvider provider = new ConfigFileAuthenticationDetailsProvider(
          configFile);

      /* Create a database tool client */
      client = DatabaseToolsClient.builder().build(provider);

      /* Create a database client */
      dbClient = DatabaseClient.builder().build(provider);
    } catch (IOException e) {
      // This test may be run in an environment having no OCI configuration
      // file. Don't fail the test suite by throwing an exception, just print
      // the error message for awareness of users.
      System.out.println(e.getMessage());
    }
  }

  /**
   * Validates the connection can be established using the Database Tools
   * Connection provdier.
   */
  @Test
  public void testConnection() throws SQLException {
    String ocid =
        TestProperties.getOrAbort(OciTestProperty.OCI_DB_TOOLS_CONNECTION_OCID_SSO);
    String url = "jdbc:oracle:thin:@config-ocidbtools:" + ocid;

    OracleDataSource ds = new OracleDataSource();
    ds.setURL(url);

    // Standard JDBC code
    Connection cn = ds.getConnection();
    Statement st = cn.createStatement();
    ResultSet rs = st.executeQuery("SELECT 'Hello, db' FROM sys.dual");
    if (rs.next())
      Assertions.assertEquals("Hello, db", rs.getString(1));
    else
      Assertions.fail("Should get 'Hello, db'");
  }

  /**
   * Verifies the properties can be obtained with the provided Database Tools
   * Connection OCID. Each referenced Database Tools Connections is configured
   * with a different type of Wallet for SSL connection.
   */
  @Test
  public void testWallet() {
    OciTestProperty[] parameters = new OciTestProperty[]{
      OciTestProperty.OCI_DB_TOOLS_CONNECTION_OCID_KEYSTORE,
      OciTestProperty.OCI_DB_TOOLS_CONNECTION_OCID_PKCS12,
      OciTestProperty.OCI_DB_TOOLS_CONNECTION_OCID_SSO
    };

    Arrays.stream(parameters)
      .map(TestProperties::getOptional)
      .filter(Objects::nonNull)
      .map(ocid -> {
        try {
          return PROVIDER.getConnectionProperties(ocid);
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
      })
      .forEach(properties ->
        Assertions.assertNotEquals(0, properties.size()));
  }

  /**
   * Verifies if the requested DB Tools Connection is DELETED, throw an
   * IllegalStateException
   **/
  @Test
  public void testGetPropertiesFromDeletedConnection() {
    String OCI_DISPLAY_NAME = "display_name_for_connection";
    String OCI_USERNAME = "admin";
    String OCI_PASSWORD_OCID = TestProperties.getOrAbort(
        OciTestProperty.OCI_PASSWORD_OCID);
    String OCI_DATABASE_OCID = TestProperties.getOrAbort(
        OciTestProperty.OCI_DATABASE_OCID);
    String OCI_COMPARTMENT_ID = TestProperties.getOrAbort(
        OciTestProperty.OCI_COMPARTMENT_ID);
    String OCI_DATABASE_CONNECTION_STRING = getConnectionStringFromAutonomousDatabase(
        OCI_DATABASE_OCID);

    /* Create new Connection */
    CreateDatabaseToolsConnectionResponse createResponse = sendCreateConnRequest(
        OCI_USERNAME, OCI_PASSWORD_OCID, OCI_DISPLAY_NAME, OCI_COMPARTMENT_ID,
        OCI_DATABASE_CONNECTION_STRING, OCI_DATABASE_OCID);
    Assertions.assertEquals(201,
        createResponse.get__httpStatusCode__()); /* The db tools connection is being created. */

    /* Retrieve OCID */
    String ocid = createResponse.getDatabaseToolsConnection().getId();

    /* Then delete Connection */
    DeleteDatabaseToolsConnectionResponse deleteResponse = sendDeleteConnRequest(
        ocid);
    Assertions.assertEquals(202,
        deleteResponse.get__httpStatusCode__()); /* Request accepted. This db tools connection will be deleted */

    GetDatabaseToolsConnectionResponse getResponse = sendGetConnRequest(ocid);
    Assertions.assertEquals(200, getResponse.get__httpStatusCode__());
    LifecycleState state = getResponse
        .getDatabaseToolsConnection()
        .getLifecycleState();
    Assertions.assertEquals(LifecycleState.Deleted, state);

    /* assertThrows */
    Assertions.assertThrows(IllegalStateException.class,
        () -> PROVIDER.getConnectionProperties(ocid));
  }

  /**
   * Verify that the cache is purged after hitting 1017 error.
   * Specifically, get connection to the same url twice, but modify the 'user'
   * every time.
   * A new db tools connection is created and deleted for this test.
   **/
  @Test
  public void testCachePurged() throws SQLException {
    String OCI_DISPLAY_NAME = "display_name_for_connection";
    String OCI_USERNAME = "admin";
    String OCI_PASSWORD_OCID = TestProperties.getOrAbort(
      OciTestProperty.OCI_PASSWORD_OCID);
    String OCI_DATABASE_OCID = TestProperties.getOrAbort(
      OciTestProperty.OCI_DATABASE_OCID);
    String OCI_COMPARTMENT_ID = TestProperties.getOrAbort(
      OciTestProperty.OCI_COMPARTMENT_ID);
    String OCI_DATABASE_CONNECTION_STRING = getConnectionStringFromAutonomousDatabase(
      OCI_DATABASE_OCID);

    /* Create new Connection */
    CreateDatabaseToolsConnectionResponse createResponse = sendCreateConnRequest(
      OCI_USERNAME, OCI_PASSWORD_OCID, OCI_DISPLAY_NAME, OCI_COMPARTMENT_ID,
      OCI_DATABASE_CONNECTION_STRING, OCI_DATABASE_OCID);
    Assertions.assertEquals(201,
      createResponse.get__httpStatusCode__()); /* The db tools connection is being created. */

    String ocid = createResponse
      .getDatabaseToolsConnection().getId(); /* Retrieve OCID of Connection */

    String url = "jdbc:oracle:thin:@config-ocidbtools:" + ocid; /* The url used to connect to Database */

    /* Set value of 'user' wrong */
    UpdateDatabaseToolsConnectionDetails updateDatabaseToolsConnectionDetails =
      UpdateDatabaseToolsConnectionOracleDatabaseDetails.builder()
        .userName(OCI_USERNAME + "wrong")
        .build();

    UpdateDatabaseToolsConnectionResponse updateResponse =
      sendUpdateConnRequest(ocid, updateDatabaseToolsConnectionDetails);
    Assertions.assertEquals(202, updateResponse.get__httpStatusCode__());

    /* Connection fails: hit 1017 */
    SQLException exception = assertThrows(SQLException.class,
      () -> tryConnection(url), "Should throw an SQLException");
    Assertions.assertEquals(exception.getErrorCode(), 1017);

    /* Set value of 'user' correct */
    UpdateDatabaseToolsConnectionDetails updateDatabaseToolsConnectionDetails2 = UpdateDatabaseToolsConnectionOracleDatabaseDetails.builder()
      .userName(OCI_USERNAME).build();

    UpdateDatabaseToolsConnectionResponse updateResponse2 =
      sendUpdateConnRequest(ocid, updateDatabaseToolsConnectionDetails2);
    Assertions.assertEquals(202, updateResponse2.get__httpStatusCode__());

    /* Connection succeeds */
    Connection conn = tryConnection(url);
    Assertions.assertNotNull(conn);

    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery("SELECT 'Hello, db' FROM sys.dual");
    Assertions.assertNotNull(rs.next());
    Assertions.assertEquals("Hello, db", rs.getString(1));

    /* Finally delete Connection */
    DeleteDatabaseToolsConnectionResponse deleteResponse = sendDeleteConnRequest(
      ocid);
    Assertions.assertEquals(202,
      deleteResponse.get__httpStatusCode__()); /* Request accepted. This db tools connection will be deleted */
  }

  /**
   * Helper function: send create DB Tools Connection request
   * @param OCI_DATABASE_OCID The OCID of the Autonomous Database
   * @param OCI_DATABASE_CONNECTION_STRING Connection String use to connect to the DB
   * @param OCI_USERNAME The database username
   * @param OCI_PASSWORD_OCID The OCID of secret containing the user password
   * @param OCI_DISPLAY_NAME Display name of the Connection to be created
   * @param OCI_COMPARTMENT_ID The OCID of compartment containing the DB Tools Connection
   * @return CreateDatabaseToolsConnectionResponse
   */
  private CreateDatabaseToolsConnectionResponse sendCreateConnRequest(
      String OCI_USERNAME, String OCI_PASSWORD_OCID, String OCI_DISPLAY_NAME,
      String OCI_COMPARTMENT_ID, String OCI_DATABASE_CONNECTION_STRING,
      String OCI_DATABASE_OCID) {

    // Ignore this test if the required configuration is missing.
    Assumptions.assumeTrue(client != null);

    /* Create a request and dependent object(s). */
    CreateDatabaseToolsConnectionDetails createDatabaseToolsConnectionDetails = CreateDatabaseToolsConnectionOracleDatabaseDetails
        .builder()
        .relatedResource(CreateDatabaseToolsRelatedResourceDetails
            .builder()
            .entityType(RelatedResourceEntityType.Autonomousdatabase)
            .identifier(OCI_DATABASE_OCID)
            .build())
        .connectionString(OCI_DATABASE_CONNECTION_STRING)
        .userName(OCI_USERNAME)
        .userPassword(DatabaseToolsUserPasswordSecretIdDetails
            .builder()
            .secretId(OCI_PASSWORD_OCID)
            .build())
        .displayName(OCI_DISPLAY_NAME)
        .compartmentId(OCI_COMPARTMENT_ID)
        .build();

    CreateDatabaseToolsConnectionRequest createDatabaseToolsConnectionRequest = CreateDatabaseToolsConnectionRequest
        .builder()
        .createDatabaseToolsConnectionDetails(
            createDatabaseToolsConnectionDetails)
        .build();

    /* Send request */
    CreateDatabaseToolsConnectionResponse response = client.createDatabaseToolsConnection(
        createDatabaseToolsConnectionRequest);
    return response;
  }

  /**
   * Helper function: send delete DB Tools Connection request
   * @param ocid The OCID of DB Tools Connection
   * @return DeleteDatabaseToolsConnectionResponse
   */
  private DeleteDatabaseToolsConnectionResponse sendDeleteConnRequest(
      String ocid) {
    // Ignore this test if the required configuration is missing.
    Assumptions.assumeTrue(client != null);

    /* Create a request and dependent object(s). */
    DeleteDatabaseToolsConnectionRequest deleteDatabaseToolsConnectionRequest = DeleteDatabaseToolsConnectionRequest
        .builder()
        .databaseToolsConnectionId(ocid)
        .build();

    /* Send request */
    DeleteDatabaseToolsConnectionResponse response = client.deleteDatabaseToolsConnection(
        deleteDatabaseToolsConnectionRequest);
    return response;
  }

  /**
   * Helper function: send get DB Tools Connection request
   * @param ocid The OCID of DB Tools Connection
   * @return GetDatabaseToolsConnectionResponse
   */
  private GetDatabaseToolsConnectionResponse sendGetConnRequest(String ocid) {
    // Ignore this test if the required configuration is missing.
    Assumptions.assumeTrue(client != null);

    /* Create a request and dependent object(s). */
    GetDatabaseToolsConnectionRequest getDatabaseToolsConnectionRequest = GetDatabaseToolsConnectionRequest
        .builder()
        .databaseToolsConnectionId(ocid)
        .build();

    /* Send request to the Client */
    GetDatabaseToolsConnectionResponse response = client.getDatabaseToolsConnection(
        getDatabaseToolsConnectionRequest);
    return response;
  }

  /**
   * Helper function: send update DB Tools Connection request
   * @param ocid The OCID of DB Tools Connection
   * @param updateDatabaseToolsConnectionDetails
   *        The updateDatabaseToolsConnectionDetails to update to this
   *        connection
   * @return UpdateDatabaseToolsConnectionResponse
   */
  private UpdateDatabaseToolsConnectionResponse sendUpdateConnRequest(String ocid, UpdateDatabaseToolsConnectionDetails updateDatabaseToolsConnectionDetails){
    /* Create a request and dependent object(s). */
    UpdateDatabaseToolsConnectionRequest updateDatabaseToolsConnectionRequest
      = UpdateDatabaseToolsConnectionRequest.builder()
      .databaseToolsConnectionId(ocid)
      .updateDatabaseToolsConnectionDetails(updateDatabaseToolsConnectionDetails)
      .build();

    /* Send request to the Client */
    UpdateDatabaseToolsConnectionResponse response = client.updateDatabaseToolsConnection(updateDatabaseToolsConnectionRequest);
    return response;
  }

  /**
   * Helper function: send get Autonomous Database request
   * @param OCI_DATABASE_OCID The OCID of the Autonomous Database
   * @return The connection string of the Autonomous Database. There are
   * several connection strings and the first one (high) is retrieved here,
   * for sake of convenience.
   **/
  @Test
  private String getConnectionStringFromAutonomousDatabase(
      String OCI_DATABASE_OCID) {

    // Ignore this test if the required configuration is missing.
    Assumptions.assumeTrue(dbClient != null);

    /* Create a request and dependent object(s). */
    GetAutonomousDatabaseRequest getAutonomousDatabaseRequest = GetAutonomousDatabaseRequest
        .builder()
        .autonomousDatabaseId(OCI_DATABASE_OCID)
        .build();

    /* Send request to the Database Client */
    GetAutonomousDatabaseResponse response = dbClient.getAutonomousDatabase(
        getAutonomousDatabaseRequest);
    Assertions.assertEquals(200, response.get__httpStatusCode__());

    String CONNECTION_STRING = response
        .getAutonomousDatabase()
        .getConnectionStrings()
        .getProfiles()
        .get(0)
        .getValue();
    return CONNECTION_STRING;
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
}
