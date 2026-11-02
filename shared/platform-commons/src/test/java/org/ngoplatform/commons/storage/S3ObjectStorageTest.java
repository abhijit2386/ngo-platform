package org.ngoplatform.commons.storage;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/** The client is built for OCI Object Storage's S3 Compatibility API (region and endpoint from settings, no network). */
class S3ObjectStorageTest {
    @Test
    void buildsForAnOciRegionAndEndpoint() {
        assertThatCode(() -> new S3ObjectStorage("https://mynamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com",
                "yogdaan-files", "access", "secret", "ap-mumbai-1")).doesNotThrowAnyException();
    }

    @Test
    void stillBuildsForLocalSeaweedFs() {
        assertThatCode(() -> new S3ObjectStorage("http://localhost:8333", "ngo-files", "dev-access", "dev-secret", "us-east-1"))
                .doesNotThrowAnyException();
    }
}
