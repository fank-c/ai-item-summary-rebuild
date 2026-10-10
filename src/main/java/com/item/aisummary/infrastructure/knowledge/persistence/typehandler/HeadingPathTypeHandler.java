package com.item.aisummary.infrastructure.knowledge.persistence.typehandler;

import com.item.aisummary.domain.knowledge.value.Heading;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** JSON 数组与有序标题列表往返；不使用依赖 Jackson 2 的默认处理器。 */
public final class HeadingPathTypeHandler extends BaseTypeHandler<List<Heading>> {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final TypeReference<List<Heading>> HEADINGS = new TypeReference<>() { };

    @Override public void setNonNullParameter(PreparedStatement statement, int index, List<Heading> value, JdbcType jdbcType)
            throws SQLException {
        String json = JSON.writeValueAsString(value);
        // H2 的 setString 会把 JSON 文本保存成 JSON 字符串，原生 UTF-8 字节才能保持数组。
        // MySQL JSON 列使用文本绑定，避免二进制字符集不能转换为 JSON 的问题。
        if ("H2".equals(statement.getConnection().getMetaData().getDatabaseProductName())) {
            statement.setBytes(index, json.getBytes(StandardCharsets.UTF_8));
        } else {
            statement.setString(index, json);
        }
    }
    @Override public List<Heading> getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getString(column));
    }
    @Override public List<Heading> getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getString(column));
    }
    @Override public List<Heading> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return decode(statement.getString(column));
    }
    private static List<Heading> decode(String value) throws SQLException {
        if (value == null) return null;
        try { return JSON.readValue(value, HEADINGS); }
        catch (JacksonException exception) { throw new SQLException("标题路径 JSON 无法还原", exception); }
    }
}
