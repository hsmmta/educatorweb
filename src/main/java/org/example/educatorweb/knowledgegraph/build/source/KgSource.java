package org.example.educatorweb.knowledgegraph.build.source;

import org.example.educatorweb.rag.model.DocumentChunk;
import java.util.List;

public interface KgSource {
    String name();
    String type();
    List<DocumentChunk> fetch();
}
