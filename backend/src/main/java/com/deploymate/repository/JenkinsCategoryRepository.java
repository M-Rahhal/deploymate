package com.deploymate.repository;

import com.deploymate.entity.JenkinsCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JenkinsCategoryRepository extends JpaRepository<JenkinsCategory, Long> {
    Optional<JenkinsCategory> findByName(String name);
    boolean existsByName(String name);
}
