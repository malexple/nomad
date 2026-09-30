package org.nomad.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.nomad.bus.LocalWakeBus;
import org.nomad.bus.RedisWakeBus;
import org.nomad.bus.WakeBus;
import org.nomad.mailbox.InMemoryMailboxStore;
import org.nomad.mailbox.MailboxStore;
import org.nomad.mailbox.PostgresMailboxStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class StoreConfig {

    @Bean
    @ConditionalOnProperty(name = "nomad.store", havingValue = "memory", matchIfMissing = true)
    MailboxStore memoryStore() {
        return new InMemoryMailboxStore();
    }

    @Bean
    @ConditionalOnProperty(name = "nomad.store", havingValue = "postgres")
    MailboxStore postgresStore(
            @Value("${nomad.pg.url}") String url,
            @Value("${nomad.pg.user}") String user,
            @Value("${nomad.pg.password}") String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        PostgresMailboxStore store = new PostgresMailboxStore(new HikariDataSource(cfg));
        store.initSchema();
        return store;
    }

    @Bean
    @ConditionalOnProperty(name = "nomad.bus", havingValue = "local", matchIfMissing = true)
    WakeBus localBus() {
        return new LocalWakeBus();
    }

    @Bean
    @ConditionalOnProperty(name = "nomad.bus", havingValue = "redis")
    WakeBus redisBus(@Value("${nomad.redis.uri}") String uri) {
        return new RedisWakeBus(uri);
    }
}
