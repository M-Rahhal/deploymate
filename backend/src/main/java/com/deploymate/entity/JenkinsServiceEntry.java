package com.deploymate.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "jenkins_service_entries",
    uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "name"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JenkinsServiceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private JenkinsCategory category;

    @Column(nullable = false, length = 200)
    private String name;

    public JenkinsServiceEntry(JenkinsCategory category, String name) {
        this.category = category;
        this.name     = name;
    }
}
