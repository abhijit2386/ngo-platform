package org.ngoplatform.commons.storage;

import java.io.InputStream;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Works with any S3-compatible store (SeaweedFS locally; any S3 service in production). */
@Component
@ConditionalOnProperty(name = "ngo.storage.type", havingValue = "s3")
public class S3ObjectStorage implements ObjectStorage {
    private final S3Client s3;
    private final String bucket;
    private volatile boolean bucketReady;

    public S3ObjectStorage(@Value("${ngo.storage.endpoint}") String endpoint,
                           @Value("${ngo.storage.bucket}") String bucket,
                           @Value("${ngo.storage.access-key}") String accessKey,
                           @Value("${ngo.storage.secret-key}") String secretKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    private void ensureBucket() {
        if (bucketReady) return;
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        ensureBucket();
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    @Override
    public InputStream get(String key) {
        ensureBucket();
        return s3.getObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String key) {
        ensureBucket();
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        ensureBucket();
        s3.deleteObject(b -> b.bucket(bucket).key(key)); // S3 delete is idempotent
    }
}
