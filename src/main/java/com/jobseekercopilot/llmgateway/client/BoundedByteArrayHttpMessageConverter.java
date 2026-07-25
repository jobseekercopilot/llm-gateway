package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;

final class BoundedByteArrayHttpMessageConverter extends ByteArrayHttpMessageConverter {
    private static final int BUFFER_SIZE = 8192;
    private final int maxResponseBytes;

    BoundedByteArrayHttpMessageConverter(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public byte[] readInternal(Class<? extends byte[]> clazz, HttpInputMessage inputMessage)
            throws IOException {
        long contentLength = inputMessage.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            throw oversizedResponse();
        }

        try (InputStream input = inputMessage.getBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     contentLength > 0 ? (int) Math.min(contentLength, maxResponseBytes) : BUFFER_SIZE
             )) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw oversizedResponse();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private ProviderFailureException oversizedResponse() {
        return new ProviderFailureException(
                ProviderFailureType.INVALID_RESPONSE,
                "The provider response exceeded the configured byte limit."
        );
    }
}
