package com.testagent.repository;

import com.testagent.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, String> {
    List<GroupMember> findByGroupId(String groupId);

    Optional<GroupMember> findByGroupIdAndUserId(String groupId, String userId);

    void deleteByGroupId(String groupId);

    List<GroupMember> findByUserId(String userId);
}
