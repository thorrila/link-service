package clients;

import com.typesafe.config.Config;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;

/**
 * Called by ClickLoggingService.logClick() after every redirect, to push a click event onto SQS.
 * The send side - ClickDrainService, elsewhere in this codebase, drains it into MySQL.
 */ 
@Singleton
public class SqsPublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;

    @Inject
    public SqsPublisher(Config config) {
        this.queueUrl = config.getString("aws.sqs.queueUrl");
        String region = config.getString("aws.region");

        // 200 sized from benchmark data, see README.
        SqsClientBuilder builder = SqsClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(ApacheHttpClient.builder().maxConnections(200));
        
        // Only set for local dev (LocalStack) - blank in production, so this hits real AWS.
        String endpointOverride = config.getString("aws.sqs.endpointOverride");
        if (!endpointOverride.isBlank()) {
            // Pin to static credentials so the SDK never reaches the default credential chain
            // (which can pick up a stale STS session token from ~/.aws) when hitting LocalStack.
            builder = builder
                .endpointOverride(URI.create(endpointOverride))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")));
        }
        this.sqsClient = builder.build();
    }

    public void publish(String messageBody) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();
        sqsClient.sendMessage(request);
    }
}