package com.siryos.behave.chatbot.guides;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuideRepository extends JpaRepository<Guide, UUID> {
    Optional<Guide> findByFilename(String filename);
    List<Guide> findAllByOrderByFilenameAsc();
}
