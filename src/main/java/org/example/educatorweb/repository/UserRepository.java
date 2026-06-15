package org.example.educatorweb.repository;

import org.example.educatorweb.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
