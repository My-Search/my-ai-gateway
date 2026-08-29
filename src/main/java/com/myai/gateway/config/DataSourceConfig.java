package com.myai.gateway.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源自定义：用 {@link SelfHealingHikariDataSource} 接管 Spring Boot 的连接池自动配置。
 *
 * <p>本 Bean 定义后，Boot 的 DataSource 自动配置因 @ConditionalOnMissingBean(DataSource) 自动退避。
 * url/username/password/driver-class-name 仍取自 spring.datasource.*（含环境变量覆盖），
 * 连接池参数继续通过 spring.datasource.hikari.* 绑定到本 Bean，
 * 与原自动配置行为一致，仅增加借出时的污染连接自愈逻辑。</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public SelfHealingHikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(SelfHealingHikariDataSource.class)
                .build();
    }
}
