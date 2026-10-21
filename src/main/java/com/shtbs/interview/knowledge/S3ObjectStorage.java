package com.shtbs.interview.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class S3ObjectStorage {
    private final S3Client client;
    private final String bucket;
    private volatile boolean bucketReady;

    public S3ObjectStorage(S3Client client, @Value("${storage.s3.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public void put(String key, byte[] bytes, String mimeType) {
        ensureBucket();
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key).contentType(mimeType).build(),
                RequestBody.fromBytes(bytes));
    }

    public byte[] get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(key).build()).asByteArray();
    }

    public void delete(String key) {
        // S3 DeleteObject succeeds when the key is already absent.
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key).build());
    }

    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) { return false; }
            throw ex;
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }
}
