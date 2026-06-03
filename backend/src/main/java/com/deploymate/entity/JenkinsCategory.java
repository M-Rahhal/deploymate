package com.deploymate.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "jenkins_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JenkinsCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    public JenkinsCategory(String name) {
        this.name = name;
    }
}
