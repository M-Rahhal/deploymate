package com.deploymate.service;

import com.deploymate.entity.JenkinsCategory;
import com.deploymate.entity.JenkinsServiceEntry;
import com.deploymate.repository.JenkinsCategoryRepository;
import com.deploymate.repository.JenkinsServiceEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class JenkinsConfigService {

    private final JenkinsCategoryRepository     jenkinsCategoryRepository;
    private final JenkinsServiceEntryRepository jenkinsServiceEntryRepository;

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        return jenkinsCategoryRepository.findAll().stream()
            .map(JenkinsCategory::getName)
            .sorted()
            .toList();
    }

    public String saveCategory(String categoryName) {
        String trimmedName = categoryName.strip();
        if (!jenkinsCategoryRepository.existsByName(trimmedName)) {
            jenkinsCategoryRepository.save(new JenkinsCategory(trimmedName));
        }
        return trimmedName;
    }

    @Transactional(readOnly = true)
    public List<String> getServiceNames(String categoryName) {
        return jenkinsCategoryRepository.findByName(categoryName.strip())
            .map(category -> jenkinsServiceEntryRepository.findByCategoryOrderByNameAsc(category).stream()
                .map(JenkinsServiceEntry::getName)
                .toList())
            .orElse(List.of());
    }

    public void saveServiceName(String categoryName, String serviceName) {
        String trimmedCategoryName = categoryName.strip();
        String trimmedServiceName  = serviceName.strip();
        JenkinsCategory category = jenkinsCategoryRepository.findByName(trimmedCategoryName)
            .orElseGet(() -> jenkinsCategoryRepository.save(new JenkinsCategory(trimmedCategoryName)));
        if (!jenkinsServiceEntryRepository.existsByCategoryAndName(category, trimmedServiceName)) {
            jenkinsServiceEntryRepository.save(new JenkinsServiceEntry(category, trimmedServiceName));
        }
    }
}
