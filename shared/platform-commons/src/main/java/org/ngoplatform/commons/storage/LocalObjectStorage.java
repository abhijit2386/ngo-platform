package org.ngoplatform.commons.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-folder storage for development (no S3 server needed). Same contract as S3: private objects addressed by
 * key, written atomically. Production uses {@link S3ObjectStorage} ({@code ngo.storage.type=s3}).
 */
@Component
@ConditionalOnProperty(name = "ngo.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    public LocalObjectStorage(@Value("${ngo.storage.local-root:.local-files}") String rootDir,
                              @Value("${ngo.storage.bucket:default}") String bucket) {
        this.root = Path.of(rootDir, bucket).toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload", ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String key) { return Files.isRegularFile(resolve(key)); }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
