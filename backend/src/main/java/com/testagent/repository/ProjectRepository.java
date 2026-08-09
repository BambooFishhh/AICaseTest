package com.testagent.repository;

import com.testagent.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE Project p SET p.status = :status WHERE p.id = :id")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    @Modifying
    @Query("UPDATE Project p SET p.techStack = :techStack WHERE p.id = :id")
    int updateTechStack(@Param("id") String id, @Param("techStack") String techStack);

    // v1.6: 更新生成进度（progress 传 null 表示清除）。@Transactional 使其自包含，
    // 供 @Async 的 runGenerate 立即提交进度给前端轮询可见。
    @Transactional
    @Modifying
    @Query("UPDATE Project p SET p.progress = :progress, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    void updateProgress(@Param("id") String id, @Param("progress") String progress);

    // v1.6: 更新失败状态并存储错误详情，同时清除进度
    @Transactional
    @Modifying
    @Query("UPDATE Project p SET p.status = :status, p.errorMessage = :errorMsg, p.progress = null, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    void updateStatusWithError(@Param("id") String id, @Param("status") String status, @Param("errorMsg") String errorMsg);
}
