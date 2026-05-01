package com.sonny.parserag.repository;

import com.sonny.parserag.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {
}
