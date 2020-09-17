package tenant.export.models;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@DynamoDbBean
public class TenantInfo {

    private String id;
    private String name;

    private static final String tableName = System.getenv("DB_TABLE");

    private DynamoDbTable<TenantInfo> TENANT_TABLE;

    public TenantInfo() {}

    public TenantInfo(AwsCredentialsProvider awsCredentialsProvider, String tenant) {
        DynamoDbClient ddb = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .credentialsProvider(awsCredentialsProvider)
            .build();

        DynamoDbEnhancedClient DDB_ENHANCED_CLIENT =
            DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();
        this.id = tenant;
        this.TENANT_TABLE = DDB_ENHANCED_CLIENT.table(tableName, TableSchema.fromBean(TenantInfo.class));
    }

    public TenantInfo(AwsCredentialsProvider awsCredentialsProvider, String tenant, String name) {
        this.id = tenant;
        this.name = name;
        DynamoDbClient ddb = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .credentialsProvider(awsCredentialsProvider)
            .build();

        DynamoDbEnhancedClient DDB_ENHANCED_CLIENT =
            DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();
        this.TENANT_TABLE = DDB_ENHANCED_CLIENT.table(tableName, TableSchema.fromBean(TenantInfo.class));
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute(value = "tenant-id")
    public String getId() {
        return this.id;
    }

    public void setId(String tenant) {
        this.id = tenant;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TenantInfo load(TenantInfo tenant) {
        return TENANT_TABLE.getItem(tenant);
    }

    public void save() {
        TENANT_TABLE.putItem(this);
    }
}
