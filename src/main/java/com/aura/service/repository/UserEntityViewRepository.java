package com.aura.service.repository;

import com.aura.service.entity.UserEntityView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserEntityViewRepository extends JpaRepository<UserEntityView, Long> {

    Optional<UserEntityView> findByUserIdAndEntityId(Long userId, Long entityId);

    @Query("SELECT v.lastSeenAt FROM UserEntityView v " +
            "WHERE v.userId = :userId AND v.entityId = :entityId")
    Optional<Instant> findLastSeen(@Param("userId") Long userId, @Param("entityId") Long entityId);

    @Query("SELECT v.entityId FROM UserEntityView v WHERE v.userId = :userId")
    List<Long> findEntityIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE UserEntityView v SET v.lastSeenAt = :lastSeenAt " +
            "WHERE v.userId = :userId AND v.entityId = :entityId")
    int touchLastSeen(
            @Param("userId") Long userId,
            @Param("entityId") Long entityId,
            @Param("lastSeenAt") Instant lastSeenAt
    );
}
