SET NAMES utf8mb4;

-- 普通聊天模型配置种子（P6 清理后：移除星火/讯飞，仅保留 DeepSeek）。
-- 说明：普通聊天（deepseek）的密钥/地址/模型已统一由服务端 ai-meeting.llm.* 提供，
-- 此处 api_key 仅作占位，真实密钥通过环境变量 LLM_API_KEY 或 admin/application-local.yaml 注入。
INSERT INTO ai_properties (id, ai_name, ai_type, api_key, api_secret, api_url, model_name, max_tokens, temperature, system_prompt, is_enabled, enable_thinking, thinking_budget_tokens, create_time, update_time, del_flag, project_id, organization_id) VALUES (3, 'DeepSeek R1', 'deepseek', 'sk-REPLACE_WITH_YOUR_KEY', null, 'https://api.deepseek.com', 'deepseek-reasoner', 8192, 0.70, '你是 AI 模拟面试平台的智能助手，请根据用户的问题提供准确、有用的回答。', 1, 1, 4096, '2026-03-04 16:15:13', '2026-03-04 16:15:13', 0, null, null);
INSERT INTO ai_properties (id, ai_name, ai_type, api_key, api_secret, api_url, model_name, max_tokens, temperature, system_prompt, is_enabled, enable_thinking, thinking_budget_tokens, create_time, update_time, del_flag, project_id, organization_id) VALUES (4, 'DeepSeek V3', 'deepseek', 'sk-REPLACE_WITH_YOUR_KEY', null, 'https://api.deepseek.com', 'deepseek-chat', 4096, 0.70, '你是 AI 模拟面试平台的智能助手，请根据用户的问题提供准确、有用的回答。', 1, 0, null, '2026-03-04 16:15:13', '2026-03-04 16:15:13', 0, null, null);
