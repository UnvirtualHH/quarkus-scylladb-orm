package io.quarkiverse.quarkus.scylladb.orm.config;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Registers the ScyllaOrmConfig mapping with SmallRye Config.
 */
public class ScyllaOrmConfigBuilderCustomizer implements ConfigBuilder {
    @Override
    public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
        return builder.withMapping(ScyllaOrmConfig.class);
    }
}
