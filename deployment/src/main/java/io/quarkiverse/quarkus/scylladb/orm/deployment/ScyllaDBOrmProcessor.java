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
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;

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

    // --- Native image support build steps ---
    // The Cassandra Java Driver 4.19.0 already ships its own META-INF/native-image/
    // configuration (reflection.json, proxy.json, native-image.properties), which GraalVM
    // picks up automatically. The build steps below supplement this with Quarkus-specific
    // registrations to ensure resources and runtime initialization are handled correctly.

    @BuildStep
    void registerCassandraDriverForNative(
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        // Register Cassandra driver policy/factory classes that are instantiated via
        // reflection from reference.conf at runtime. The driver's own reflection.json
        // covers these, but Quarkus may not propagate all META-INF/native-image configs
        // from third-party JARs, so we register them explicitly as a safety net.
        String[] driverClasses = {
                "com.datastax.oss.driver.internal.core.loadbalancing.DefaultLoadBalancingPolicy",
                "com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy",
                "com.datastax.oss.driver.internal.core.connection.ExponentialReconnectionPolicy",
                "com.datastax.oss.driver.internal.core.connection.ConstantReconnectionPolicy",
                "com.datastax.oss.driver.internal.core.retry.DefaultRetryPolicy",
                "com.datastax.oss.driver.internal.core.specex.NoSpeculativeExecutionPolicy",
                "com.datastax.oss.driver.internal.core.time.AtomicTimestampGenerator",
                "com.datastax.oss.driver.internal.core.time.ServerSideTimestampGenerator",
                "com.datastax.oss.driver.internal.core.tracker.NoopRequestTracker",
                "com.datastax.oss.driver.internal.core.tracker.RequestLogger",
                "com.datastax.oss.driver.internal.core.session.throttling.PassThroughRequestThrottler",
                "com.datastax.oss.driver.internal.core.metadata.NoopNodeStateListener",
                "com.datastax.oss.driver.internal.core.metadata.schema.NoopSchemaChangeListener",
                "com.datastax.oss.driver.internal.core.addresstranslation.PassThroughAddressTranslator",
                "com.datastax.oss.driver.internal.core.ssl.DefaultSslEngineFactory",
                "com.datastax.oss.driver.internal.core.metrics.DefaultMetricsFactory",
                "com.datastax.oss.driver.internal.core.metrics.NoopMetricsFactory",
                "com.datastax.oss.driver.internal.core.metrics.DefaultMetricIdGenerator",
                "com.datastax.oss.driver.internal.core.auth.PlainTextAuthProvider",
                "com.datastax.oss.driver.internal.core.type.codec.registry.DefaultCodecRegistry"
        };

        for (String className : driverClasses) {
            reflectiveClass.produce(ReflectiveClassBuildItem.builder(className)
                    .constructors(true)
                    .methods(true)
                    .build());
        }
    }

    @BuildStep
    void registerNativeResources(
            BuildProducer<NativeImageResourcePatternsBuildItem> resourcePatterns) {
        // Ensure the Cassandra driver's HOCON configuration file is included in the
        // native image. Without reference.conf the driver cannot initialize.
        resourcePatterns.produce(NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("reference.conf")
                .build());
    }

    @BuildStep
    void registerRuntimeInitializedClasses(
            BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        // Netty classes that must be initialized at runtime, not build time.
        // Required for Cassandra driver's Netty-based transport.
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "io.netty.channel.epoll.Epoll"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "io.netty.channel.epoll.EpollEventLoop"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "io.netty.channel.epoll.Native"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "io.netty.handler.ssl.BouncyCastleAlpnSslUtils"));
    }

    @BuildStep
    void registerServiceProviders(
            BuildProducer<ServiceProviderBuildItem> serviceProviders) {
        // Register the Cassandra driver's default session factory for ServiceLoader
        serviceProviders.produce(new ServiceProviderBuildItem(
                "com.datastax.oss.driver.api.core.session.SessionBuilder",
                "com.datastax.oss.driver.internal.core.session.DefaultSession"));
    }
}
