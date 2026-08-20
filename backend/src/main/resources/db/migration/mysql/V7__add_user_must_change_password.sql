-- v6.3: 首登/初始密码强制修改标记
ALTER TABLE users ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0;
