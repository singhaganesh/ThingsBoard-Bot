package com.seple.ThingsBoard_Bot.repository;

import com.seple.ThingsBoard_Bot.entity.HierarchyNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, String> {

    List<HierarchyNode> findByCustomerId(String customerId);

    List<HierarchyNode> findByCustomerIdAndIsLeaf(String customerId, Boolean isLeaf);

    List<HierarchyNode> findByCustomerIdAndParentId(String customerId, String parentId);

    Optional<HierarchyNode> findByCustomerIdAndDisplayNameIgnoreCase(String customerId, String displayName);

    Optional<HierarchyNode> findByTbDeviceId(java.util.UUID tbDeviceId);

    List<HierarchyNode> findByCustomerIdAndNodeType(String customerId, String nodeType);

    boolean existsByCustomerIdAndDisplayNameIgnoreCase(String customerId, String displayName);
}
