package io.quarkiverse.quarkus.scylladb.orm.config;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilderCustomizer;

/**
 * Registers the ScyllaOrmConfig mapping with SmallRye Config.
 */
public class ScyllaOrmConfigBuilderCustomizer implements SmallRyeConfigBuilderCustomizer {
    @Override
    public void configBuilder(SmallRyeConfigBuilder builder) {
        builder.withMapping(ScyllaOrmConfig.class);
    }
}
