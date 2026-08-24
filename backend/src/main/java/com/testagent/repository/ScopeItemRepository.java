package com.testagent.repository;

import com.testagent.entity.ScopeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScopeItemRepository extends JpaRepository<ScopeItem, String> {

    List<ScopeItem> findByDefinitionIdOrderByItemTypeAscIdAsc(String definitionId);

    long countByDefinitionId(String definitionId);

    void deleteByDefinitionId(String definitionId);

    @Modifying
    void deleteById(String id);
}
