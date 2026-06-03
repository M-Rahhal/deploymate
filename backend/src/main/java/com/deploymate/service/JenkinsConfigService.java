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

    private final JenkinsCategoryRepository     categoryRepo;
    private final JenkinsServiceEntryRepository entryRepo;

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        return categoryRepo.findAll().stream()
            .map(JenkinsCategory::getName)
            .sorted()
            .toList();
    }

    /** Saves category if not already stored. Returns the category name. */
    public String saveCategory(String name) {
        String trimmed = name.strip();
        if (!categoryRepo.existsByName(trimmed)) {
            categoryRepo.save(new JenkinsCategory(trimmed));
        }
        return trimmed;
    }

    @Transactional(readOnly = true)
    public List<String> getServiceNames(String categoryName) {
        return categoryRepo.findByName(categoryName.strip())
            .map(cat -> entryRepo.findByCategoryOrderByNameAsc(cat).stream()
                .map(JenkinsServiceEntry::getName)
                .toList())
            .orElse(List.of());
    }

    /** Saves the (category, serviceName) pair if not already stored. */
    public void saveServiceName(String categoryName, String serviceName) {
        String trimmedCat  = categoryName.strip();
        String trimmedName = serviceName.strip();
        JenkinsCategory cat = categoryRepo.findByName(trimmedCat)
            .orElseGet(() -> categoryRepo.save(new JenkinsCategory(trimmedCat)));
        if (!entryRepo.existsByCategoryAndName(cat, trimmedName)) {
            entryRepo.save(new JenkinsServiceEntry(cat, trimmedName));
        }
    }
}
