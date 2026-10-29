package com.llmrag.llm_rag_backend.repository;

import com.llmrag.llm_rag_backend.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document,Long> {

    boolean existsByContentHash(String contentHash);

    List<Document> findAllByOrderByUploadTimestampDesc();
}
