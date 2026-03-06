package com.seple.ThingsBoard_Bot.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.seple.ThingsBoard_Bot.model.entity.Conversation;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
}
