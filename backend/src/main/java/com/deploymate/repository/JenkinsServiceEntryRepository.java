package com.deploymate.repository;

import com.deploymate.entity.JenkinsCategory;
import com.deploymate.entity.JenkinsServiceEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JenkinsServiceEntryRepository extends JpaRepository<JenkinsServiceEntry, Long> {
    List<JenkinsServiceEntry> findByCategoryOrderByNameAsc(JenkinsCategory category);
    boolean existsByCategoryAndName(JenkinsCategory category, String name);
}
