package com.testagent.repository;

import com.testagent.entity.LlmResultCache;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmResultCacheRepository extends JpaRepository<LlmResultCache, String> {
}
