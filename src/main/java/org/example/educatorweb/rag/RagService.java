package org.example.educatorweb.rag;

import org.example.educatorweb.rag.model.DocumentSnippet;
import java.util.List;
import java.util.Map;

public interface RagService {
    List<DocumentSnippet> retrieve(String userId, String query, int topK);
    List<Map<String, Object>> listDocuments(String userId);
}
