package com.item.aisummary.infrastructure.knowledge.persistence.typehandler;

import com.item.aisummary.domain.knowledge.value.Heading;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 知识片段标题路径列表（List<Heading>）与数据库 JSON 字段的 MyBatis 类型处理器。
 *
 * <p>基于 Jackson 3 处理双向序列化；区分 H2（字节绑定）与 MySQL（字符串绑定）驱动方言。
 */
public final class HeadingPathTypeHandler extends BaseTypeHandler<List<Heading>> {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final TypeReference<List<Heading>> HEADINGS = new TypeReference<>() {};

    /**
     * 将有序标题列表序列化为 JSON 字符串并绑定到 SQL 参数中。
     *
     * <p>根据底层数据库驱动方言选择绑定方式：H2 将 setString 写入的字符串保存为带有双引号的 JSON 字符串值，
     * 因此对 H2 采用 UTF-8 字节数组绑定以保持其 JSON 格式；对于 MySQL 等关系型数据库，则采用标准文本绑定以避免字符集转换异常。
     *
     * @param statement PreparedStatement 实例
     * @param index SQL 参数位置（1-indexed）
     * @param value 待写入的标题列表
     * @param jdbcType 目标 JDBC 类型
     * @throws SQLException 序列化失败或数据库参数绑定失败
     */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, List<Heading> value, JdbcType jdbcType)
            throws SQLException {
        String json = JSON.writeValueAsString(value);
        // H2 的 setString 会把 JSON 文本保存成 JSON 字符串，因此使用 UTF-8 字节绑定。
        // MySQL JSON 列使用文本绑定，避免二进制字符集不能转换为 JSON。
        if ("H2".equals(statement.getConnection().getMetaData().getDatabaseProductName())) {
            statement.setBytes(index, json.getBytes(StandardCharsets.UTF_8));
        } else {
            statement.setString(index, json);
        }
    }

    /**
     * 从根据列名获取的查询结果中提取并反序列化有序标题列表。
     *
     * @param result ResultSet 结果集
     * @param column 结果列名
     * @return 反序列化后的标题列表，若列为 NULL 则返回 null
     * @throws SQLException 读取或反序列化失败
     */
    @Override
    public List<Heading> getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getString(column));
    }

    /**
     * 从根据列索引获取的查询结果中提取并反序列化有序标题列表。
     *
     * @param result ResultSet 结果集
     * @param column 列索引（1-indexed）
     * @return 反序列化后的标题列表，若列为 NULL 则返回 null
     * @throws SQLException 读取或反序列化失败
     */
    @Override
    public List<Heading> getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getString(column));
    }

    /**
     * 从存储过程 CallableStatement 中提取并反序列化有序标题列表。
     *
     * @param statement CallableStatement 实例
     * @param column 列索引（1-indexed）
     * @return 反序列化后的标题列表，若输出为 NULL 则返回 null
     * @throws SQLException 读取或反序列化失败
     */
    @Override
    public List<Heading> getNullableResult(CallableStatement statement, int column)
            throws SQLException {
        return decode(statement.getString(column));
    }

    /**
     * 将 JSON 文本解析还原为有序标题列表。
     *
     * @param value 数据库中读取的 JSON 字符串
     * @return 反序列化生成的 Heading 列表；若输入为 null 则直接返回 null
     * @throws SQLException 当 JSON 格式损坏无法还原为 List&lt;Heading&gt; 时抛出
     */
    private static List<Heading> decode(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readValue(value, HEADINGS);
        } catch (JacksonException exception) {
            throw new SQLException("标题路径 JSON 无法还原", exception);
        }
    }
}
