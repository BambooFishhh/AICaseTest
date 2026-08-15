-- v5.1 migration fix: enlarge H2 -> MySQL migrated JSON/Markdown columns
ALTER TABLE projects
    MODIFY tech_stack LONGTEXT,
    MODIFY settings LONGTEXT,
    MODIFY prd_content LONGTEXT,
    MODIFY error_message LONGTEXT;

ALTER TABLE test_cases
    MODIFY preconditions LONGTEXT,
    MODIFY steps LONGTEXT,
    MODIFY expected_results LONGTEXT,
    MODIFY state_machine_ref LONGTEXT,
    MODIFY structured_steps LONGTEXT,
    MODIFY api_endpoints LONGTEXT,
    MODIFY test_data LONGTEXT,
    MODIFY execution_hints LONGTEXT;

ALTER TABLE test_case_versions
    MODIFY snapshot LONGTEXT;

ALTER TABLE execution_record
    MODIFY summary TEXT,
    MODIFY error_message TEXT,
    MODIFY test_case_snapshot LONGTEXT;

ALTER TABLE execution_step
    MODIFY action TEXT,
    MODIFY target TEXT,
    MODIFY error TEXT;

ALTER TABLE state_machines
    MODIFY description LONGTEXT,
    MODIFY states LONGTEXT,
    MODIFY transitions LONGTEXT,
    MODIFY forbidden_transitions LONGTEXT,
    MODIFY sources LONGTEXT;

ALTER TABLE code_analysis
    MODIFY frontend_result LONGTEXT,
    MODIFY backend_result LONGTEXT,
    MODIFY error_message LONGTEXT;

ALTER TABLE mindmaps
    MODIFY statistics LONGTEXT;

ALTER TABLE test_suites
    MODIFY case_ids LONGTEXT;

ALTER TABLE system_settings
    MODIFY setting_value LONGTEXT;
