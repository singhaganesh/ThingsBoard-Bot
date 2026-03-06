package com.seple.ThingsBoard_Bot.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.seple.ThingsBoard_Bot.model.entity.Message;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByTimestampAsc(UUID conversationId);
}
