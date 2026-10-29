
package com.llmrag.llm_rag_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class OllamaEmbeddingService {

    private static final String OLLAMA_URL =
            "http://localhost:11434/api/embed";

    private static final String MODEL_NAME =
            "nomic-embed-text";

    private static final int EXPECTED_DIMENSIONS = 768;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaEmbeddingService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<Double> generateEmbedding(String text)
            throws IOException, InterruptedException {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Text must not be empty."
            );
        }

        String requestBody = objectMapper.writeValueAsString(
                new EmbeddingRequest(MODEL_NAME, text)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw new IOException(
                    "Ollama embedding request failed. HTTP status: "
                            + response.statusCode()
                            + ", response: "
                            + response.body()
            );
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode embeddingNode = root.path("embeddings").path(0);

        if (!embeddingNode.isArray()) {
            throw new IOException(
                    "Ollama response does not contain an embedding."
            );
        }

        List<Double> embedding = new ArrayList<>();

        for (JsonNode value : embeddingNode) {
            if (!value.isNumber()) {
                throw new IOException(
                        "Ollama returned a non-numeric embedding value."
                );
            }

            embedding.add(value.doubleValue());
        }

        if (embedding.size() != EXPECTED_DIMENSIONS) {
            throw new IOException(
                    "Expected " + EXPECTED_DIMENSIONS
                            + " embedding dimensions but received "
                            + embedding.size()
            );
        }

        return embedding;
    }

    private record EmbeddingRequest(String model, String input) {
    }
}
