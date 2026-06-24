package org.example.educatorweb.rag;

import org.example.educatorweb.rag.model.DocumentSnippet;
import java.util.List;

public interface RagService {
    List<DocumentSnippet> retrieve(String userId, String query, int topK);
}
