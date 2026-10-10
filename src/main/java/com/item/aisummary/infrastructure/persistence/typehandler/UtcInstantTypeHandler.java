package com.item.aisummary.infrastructure.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * 将无时区的 DATETIME(6) 统一解释为 UTC，不受 JVM 默认时区影响。 写入时转换成 UTC LocalDateTime，读取时按同一时区恢复 Instant。
 * 该约定要求连接与会话使用 UTC，不能据此推断历史 DATETIME 原本的时区。
 */
@MappedTypes(Instant.class)
public final class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    /** 将 Instant 转换为 UTC LocalDateTime 并存入 PreparedStatement。 */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, Instant value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    /** 从 ResultSet 列名读取并还原为 UTC Instant。 */
    @Override
    public Instant getNullableResult(ResultSet result, String column) throws SQLException {
        return toInstant(result.getObject(column, LocalDateTime.class));
    }

    /** 从 ResultSet 列索引读取并还原为 UTC Instant。 */
    @Override
    public Instant getNullableResult(ResultSet result, int column) throws SQLException {
        return toInstant(result.getObject(column, LocalDateTime.class));
    }

    /** 从 CallableStatement 列索引读取并还原为 UTC Instant。 */
    @Override
    public Instant getNullableResult(CallableStatement statement, int column) throws SQLException {
        return toInstant(statement.getObject(column, LocalDateTime.class));
    }

    /** 将 UTC LocalDateTime 转换为 Instant。 */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
