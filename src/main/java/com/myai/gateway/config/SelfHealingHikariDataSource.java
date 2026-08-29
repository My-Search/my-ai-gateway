package com.myai.gateway.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 自愈型 Hikari 数据源：借出连接时修复「autocommit 标志残留 false」的污染连接。
 *
 * <p>背景（sqlite-jdbc 3.45.3.0 + transaction_mode=IMMEDIATE）：驱动在
 * {@code setAutoCommit(false)} 时先把 autocommit 标志置 false，再执行
 * {@code begin immediate;}。若 BEGIN 因写锁竞争等 busy_timeout 超时失败，连接处于
 * 「JDBC 标志 autocommit=false 但 SQLite 无活动事务」的污染状态；Hikari 的
 * ProxyConnection 在 delegate 调用抛异常时不标记 dirty-bit，归还时不做任何重置，
 * 污染连接会重新回到池中。此后：</p>
 * <ul>
 *   <li>非事务路径：MyBatis 会话关闭前强制 commit → 驱动无条件执行 {@code commit;}
 *       → "cannot commit - no transaction is active"（热路径随机报错的直接来源）；</li>
 *   <li>事务路径：Spring 见 getAutoCommit() 已是 false 会跳过 BEGIN，事务语义静默失效；</li>
 *   <li>两条路径都不会恢复标志 → 污染永不自愈，直到连接因 maxLifetime 退役。</li>
 * </ul>
 *
 * <p>修复方式：借出时刻连接必然处于池默认的 autocommit=true 状态（借用方尚未拿到连接），
 * 此处读到 false 必为上次借用的异常残留，不存在误伤正常连接的可能。修复动作：
 * 先 {@code rollback()} 丢弃可能残留的事务（无事务时驱动抛 "cannot rollback ..."，
 * 属预期，吞掉）；再 {@code setAutoCommit(true)}——驱动的标志翻转发生在
 * {@code commit;} 执行之前，即使后者因无事务抛错，标志也已恢复 true，连接即恢复健康。</p>
 */
public class SelfHealingHikariDataSource extends HikariDataSource {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingHikariDataSource.class);

    @Override
    public Connection getConnection() throws SQLException {
        return heal(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return heal(super.getConnection(username, password));
    }

    private Connection heal(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            return connection;
        }
        log.warn("借出的连接 autocommit=false（上次借用异常残留的污染连接），执行自愈: {}", connection);
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("污染连接自愈恢复 autocommit 失败: {}", e.getMessage());
        }
        return connection;
    }
}
