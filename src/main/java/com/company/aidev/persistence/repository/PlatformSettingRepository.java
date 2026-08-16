package com.company.aidev.persistence.repository;

import com.company.aidev.persistence.entity.PlatformSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSettingEntity, String> {}
