package io.quarkiverse.quarkus.scylladb.orm.deployment;

import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfig;
import io.quarkiverse.quarkus.scylladb.orm.config.ScyllaOrmConfigBuilderCustomizer;
import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapperRegistry;
import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandlerRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.ReactiveRepositoryRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.RepositoryRegistry;
import io.quarkiverse.quarkus.scylladb.orm.runtime.CqlSessionProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

class ScyllaDBOrmProcessor {

    private static final String FEATURE = "scylladb-orm";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerBeans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(CqlSessionProducer.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(EntityMapperRegistry.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(TypeHandlerRegistry.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(RepositoryRegistry.class));
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(ReactiveRepositoryRegistry.class));
    }

    @BuildStep
    void registerConfigMapping(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        // Register config interfaces for reflection (needed for native builds)
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.AuthConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.PoolConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.RequestConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.SslConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.SchemaConfig.class)
                .methods(true)
                .fields(true)
                .build());
        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ScyllaOrmConfig.ReconnectionConfig.class)
                .methods(true)
                .fields(true)
                .build());
    }

    @BuildStep
    void registerConfigCustomizer(
            BuildProducer<StaticInitConfigBuilderBuildItem> staticInitConfig,
            BuildProducer<RunTimeConfigBuilderBuildItem> runTimeConfig) {
        // Register config mapping for both static init and runtime phases
        staticInitConfig.produce(new StaticInitConfigBuilderBuildItem(ScyllaOrmConfigBuilderCustomizer.class.getName()));
        runTimeConfig.produce(new RunTimeConfigBuilderBuildItem(ScyllaOrmConfigBuilderCustomizer.class.getName()));
    }
}
