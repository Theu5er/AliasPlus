package com.altdetector.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 表结构定义。
 *
 * <p>启动时执行一次，全部使用 {@code IF NOT EXISTS}，重复执行无副作用。
 * 字段类型只选 H2 与 MySQL 都支持的最小子集，将来换库不用改 DDL。
 */
final class SqlSchema {

    private static final String[] STATEMENTS = {
            // 封禁记录：一行 = 某个账号因某个来源被封（同号同来源只留一条）
            "CREATE TABLE IF NOT EXISTS ban_records ("
                    + " uuid VARCHAR(64) NOT NULL,"
                    + " name VARCHAR(64) NOT NULL,"
                    + " source VARCHAR(32) NOT NULL,"
                    + " trigger_uuid VARCHAR(64),"
                    + " ts BIGINT NOT NULL,"
                    + " PRIMARY KEY (uuid, source))",

            // 玩家档案
            "CREATE TABLE IF NOT EXISTS players ("
                    + " uuid VARCHAR(64) PRIMARY KEY,"
                    + " name VARCHAR(64) NOT NULL,"
                    + " created_at BIGINT NOT NULL)",

            // 历史名字
            "CREATE TABLE IF NOT EXISTS aliases ("
                    + " uuid VARCHAR(64) NOT NULL,"
                    + " alias_name VARCHAR(64) NOT NULL,"
                    + " PRIMARY KEY (uuid, alias_name))",

            // 登录标识符（DeviceId / SelfSignedId / ClientRandomId / NetworkID）
            "CREATE TABLE IF NOT EXISTS identifiers ("
                    + " uuid VARCHAR(64) NOT NULL,"
                    + " kind VARCHAR(32) NOT NULL,"
                    + " id_value VARCHAR(128) NOT NULL,"
                    + " ts BIGINT NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY (uuid, kind))",
            "CREATE INDEX IF NOT EXISTS idx_identifier_value ON identifiers (kind, id_value)",

            // 资源包指纹
            "CREATE TABLE IF NOT EXISTS packs ("
                    + " uuid VARCHAR(64) PRIMARY KEY,"
                    + " pack_uuid VARCHAR(64) NOT NULL,"
                    + " confirmed TINYINT NOT NULL DEFAULT 0,"
                    + " ts BIGINT NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_pack_uuid ON packs (pack_uuid)",

            // 资源包命中关系
            "CREATE TABLE IF NOT EXISTS pack_claims ("
                    + " owner VARCHAR(64) NOT NULL,"
                    + " claimer VARCHAR(64) NOT NULL,"
                    + " PRIMARY KEY (owner, claimer))",

            // Blob 指纹
            "CREATE TABLE IF NOT EXISTS blobs ("
                    + " uuid VARCHAR(64) PRIMARY KEY,"
                    + " blob_id VARCHAR(32) NOT NULL,"
                    + " ts BIGINT NOT NULL DEFAULT 0)",

            // Blob 命中关系
            "CREATE TABLE IF NOT EXISTS blob_claims ("
                    + " owner VARCHAR(64) NOT NULL,"
                    + " claimer VARCHAR(64) NOT NULL,"
                    + " PRIMARY KEY (owner, claimer))",

            // 惩罚标记
            "CREATE TABLE IF NOT EXISTS flags ("
                    + " uuid VARCHAR(64) PRIMARY KEY,"
                    + " punished TINYINT NOT NULL DEFAULT 0)"
    };

    private SqlSchema() {
    }

    static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : STATEMENTS) {
                statement.execute(ddl);
            }
        }
    }
}
