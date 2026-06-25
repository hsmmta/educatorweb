package org.example.educatorweb.profile.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByStudentIdOrderByCreatedAtDesc(String studentId);
    Optional<ChatSession> findFirstByStudentIdAndSessionTypeOrderByCreatedAtDesc(String studentId, String sessionType);
}