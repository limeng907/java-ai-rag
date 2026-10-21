package com.shtbs.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseCreateDTO(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description) {}
