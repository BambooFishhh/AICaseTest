package com.testagent.repository;

import com.testagent.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
