package io.quarkiverse.quarkus.scylladb.orm.deployment;

import io.quarkiverse.quarkus.scylladb.orm.mapping.EntityMapperRegistry;
import io.quarkiverse.quarkus.scylladb.orm.processor.TypeHandlerRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.ReactiveRepositoryRegistry;
import io.quarkiverse.quarkus.scylladb.orm.repository.RepositoryRegistry;
import io.quarkiverse.quarkus.scylladb.orm.runtime.CqlSessionProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

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
}
