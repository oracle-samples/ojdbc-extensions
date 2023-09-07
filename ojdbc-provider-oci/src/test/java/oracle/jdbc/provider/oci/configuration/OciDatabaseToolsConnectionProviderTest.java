package oracle.jdbc.provider.oci.configuration;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.databasetools.DatabaseToolsClient;
import com.oracle.bmc.databasetools.model.*;
import com.oracle.bmc.databasetools.requests.CreateDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.requests.DeleteDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.requests.GetDatabaseToolsConnectionRequest;
import com.oracle.bmc.databasetools.responses.CreateDatabaseToolsConnectionResponse;
import com.oracle.bmc.databasetools.responses.DeleteDatabaseToolsConnectionResponse;
import com.oracle.bmc.databasetools.responses.GetDatabaseToolsConnectionResponse;
import oracle.jdbc.provider.TestProperties;
import oracle.jdbc.provider.oci.OciTestProperty;
import oracle.jdbc.spi.OracleConfigurationProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Verifies the {@link OciDatabaseToolsConnectionProvider} as implementing
 * behavior specified by its JavaDoc.
 */

public class OciDatabaseToolsConnectionProviderTest {
  private static final OracleConfigurationProvider PROVIDER =
      OracleConfigurationProvider.find("ocidbtools");

  private static DatabaseToolsClient client;
  static {
    try {
      ConfigFileReader.ConfigFile configFile = ConfigFileReader.parseDefault();
      AuthenticationDetailsProvider provider = new ConfigFileAuthenticationDetailsProvider(configFile);

      /* Create a service client */
      client = DatabaseToolsClient.builder().build(provider);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Verifies the properties can be obtained using the provided Database Tools
   * Connection OCID.
   */
  @Test
  public void testGetProperties() throws SQLException {
    String ocid =
        TestProperties.getOrAbort(OciTestProperty.OCI_DB_TOOLS_CONNECTION_OCID);
    Properties props = PROVIDER.getConnectionProperties(ocid);
    Assertions.assertNotEquals(0, props.size());
  }

  /**
   * Verifies if the requested DB Tools Connection is DELETED, throw an
   * IllegalStateException
   **/
  @Test
  public void testGetPropertiesFromDeletedConneciton() throws IOException {
    String OCI_USERNAME = TestProperties.getOrAbort(
        OciTestProperty.OCI_USERNAME);
    String OCI_PASSWORD_OCID = TestProperties.getOrAbort(
        OciTestProperty.OCI_PASSWORD_OCID);
    String OCI_DISPLAY_NAME = TestProperties.getOrAbort(
        OciTestProperty.OCI_DISPLAY_NAME);
    String OCI_DATABASE_OCID = TestProperties.getOrAbort(
        OciTestProperty.OCI_DATABASE_OCID);
    String OCI_COMPARTMENT_ID = TestProperties.getOrAbort(
        OciTestProperty.OCI_COMPARTMENT_ID);
    String OCI_DATABASE_CONNECTION_STRING = TestProperties.getOrAbort(
        OciTestProperty.OCI_DATABASE_CONNECTION_STRING);

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
      String OCI_DATABASE_OCID) throws IOException {

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
      String ocid) throws IOException {

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
  private GetDatabaseToolsConnectionResponse sendGetConnRequest(String ocid)
      throws IOException {

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
}
