package tenant.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import tenant.export.errors.GatewayError;

import com.amazon.aws.partners.saasfactory.TokenVendingMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import net.lingala.zip4j.ZipFile;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import tenant.export.models.TenantInfo;


/**
 * Handler for requests to Lambda function.
 */
public class ApiGatewayHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ApiGatewayHandler.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final S3Client s3 = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    private final String role = System.getenv("ROLE");
    private final String templateBucket = System.getenv("TEMPLATE_BUCKET");
    private final String templateKey = System.getenv("TEMPLATE_KEY");
    private final String tmp = "/tmp";
    private final Path templateFilePath = Paths.get(tmp + "/templates.zip");

    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        switch(input.getHttpMethod().toLowerCase()) {
            case "get":
                return handleGetRequest(input, context);
            case "post":
                return handlePostRequest(input, context);
            default:
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(405);
        }
    };

    public APIGatewayProxyResponseEvent handlePostRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        getTemplateFile(context);

        // we vending the token by extracting tenant ID from the JWT token contained in the request headers
        TokenVendingMachine tokenVendingMachine = new TokenVendingMachine();
        final AwsCredentialsProvider awsCredentialsProvider =
            tokenVendingMachine.vendTokenNoJwtValidation(input.getHeaders(), role);

        // we parse the body of the POST request, currently we only accept a 'name' parameter to
        // be written to DynamoDB, anything else will be ignored
        Map<String, String> body;
        try {
            TypeReference<Map<String,String>> typeRef = new TypeReference<Map<String,String>>() {};
            body = mapper.readValue(input.getBody(), typeRef);
        } catch (JsonProcessingException e) {
            logger.error("Error parsing JSON body.", e);
            throw new RuntimeException(createBadRequestResponse(context.getAwsRequestId(),
                "Error parsing JSON body."));
        }

        String tenant = tokenVendingMachine.getTenant();
        logger.info("TENANT ID: " + tenant);

        // TenantInfo class encapsulates writing to DynamoDB using the enhanced DynamoDB
        // client, which allows us to use POJOs
        TenantInfo tInfo = new TenantInfo(awsCredentialsProvider, tenant, body.get("name"));
        tInfo.save();

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        return new APIGatewayProxyResponseEvent()
            .withHeaders(headers)
            .withStatusCode(201);
    }

    public APIGatewayProxyResponseEvent handleGetRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        getTemplateFile(context);

        // we vending the token by extracting tenant ID from the JWT token contained in the request headers
        TokenVendingMachine tokenVendingMachine = new TokenVendingMachine();
        final AwsCredentialsProvider awsCredentialsProvider =
            tokenVendingMachine.vendTokenNoJwtValidation(input.getHeaders(), role);

        String tenant = tokenVendingMachine.getTenant();
        logger.info("TENANT ID: " + tenant);

        // TenantInfo class encapsulates writing to DynamoDB using the enhanced DynamoDB
        // client, which allows us to use POJOs
        TenantInfo tInfo = new TenantInfo(awsCredentialsProvider, tenant);
        tInfo = tInfo.load(tInfo);

        String body;
        try {
            body = mapper.writeValueAsString(tInfo);
        } catch (JsonProcessingException e) {
            logger.error("Error parsing JSON body.", e);
            throw new RuntimeException(createBadRequestResponse(context.getAwsRequestId(),
                "Error parsing JSON body."));
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        return new APIGatewayProxyResponseEvent()
            .withHeaders(headers)
            .withBody(body)
            .withStatusCode(200);
    }

    private String createInternalServerErrorResponse(String requestId, String message) {
        try {
            GatewayError error = new GatewayError("InternalServerError",
                "500", requestId, message);
            return mapper.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error encoding JSON response");
        }
    }

    private String createBadRequestResponse(String requestId, String message) {
        try {
            GatewayError error = new GatewayError("Bad Request",
                "400", requestId, message);
            return mapper.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error encoding JSON response");
        }
    }

    private void getTemplateFile(Context context) {
        // we only download the policies from S3 if it doesn't exist on the filesystem already
        // from a previous lambda invocation
        if(Files.notExists(templateFilePath)) {
            logger.info("Templates zip file not found, downloading from S3...");
            s3.getObject(GetObjectRequest.builder().bucket(templateBucket).key(templateKey).build(),
                ResponseTransformer.toFile(templateFilePath));
            try {
                ZipFile zipFile = new ZipFile(templateFilePath.toFile());
                zipFile.extractAll(tmp + "/policies");
            } catch (IOException e) {
                logger.error("Could not unzip template file.", e);
                throw new RuntimeException(createInternalServerErrorResponse(context.getAwsRequestId(),
                    "Error unzipping file."));
            }
        }
    }
}
