package com.seple.ThingsBoard_Bot.repository;

import com.seple.ThingsBoard_Bot.entity.BranchAncestorPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchAncestorPathRepository extends JpaRepository<BranchAncestorPath, String> {

    List<BranchAncestorPath> findByCustomerId(String customerId);

    Optional<BranchAncestorPath> findByBranchNodeIdAndCustomerId(String branchNodeId, String customerId);

    boolean existsByBranchNodeId(String branchNodeId);

    void deleteByCustomerId(String customerId);
}