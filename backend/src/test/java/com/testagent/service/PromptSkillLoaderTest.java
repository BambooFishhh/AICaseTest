package com.testagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSkillLoaderTest {

    @Test
    void loadsExistingSkillTemplate() {
        PromptSkillLoader loader = new PromptSkillLoader();

        String prompt = loader.load("prd-analysis", "fallback");

        assertTrue(prompt.contains("需求分析专家"));
    }

    @Test
    void fallsBackWhenTemplateMissing() {
        PromptSkillLoader loader = new PromptSkillLoader();

        assertEquals("fallback", loader.load("not-exist", "fallback"));
    }
}
