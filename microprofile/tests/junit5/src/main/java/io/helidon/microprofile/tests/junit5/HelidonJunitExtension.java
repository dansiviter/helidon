/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.microprofile.tests.junit5;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.inject.spi.BeforeBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.helidon.config.mp.MpConfigSources;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Junit5 extension to support Helidon CDI container in tests.
 */
class HelidonJunitExtension implements BeforeAllCallback,
                                       AfterAllCallback,
                                       BeforeEachCallback,
                                       AfterEachCallback,
                                       InvocationInterceptor,
                                       ParameterResolver {
    private static final Set<Class<? extends Annotation>> HELIDON_TEST_ANNOTATIONS =
            Set.of(AddBean.class, AddConfig.class, AddExtension.class, Configuration.class);

    private final List<AddExtension> classLevelExtensions = new ArrayList<>();
    private final List<AddBean> classLevelBeans = new ArrayList<>();
    private final ConfigMeta classLevelConfigMeta = new ConfigMeta();
    private boolean classLevelDisableDiscovery = false;
    private boolean resetPerTest;

    private Class<?> testClass;
    private ConfigProviderResolver configProviderResolver;
    private Config config;
    private SeContainer container;

    @SuppressWarnings("unchecked")
    @Override
    public void beforeAll(ExtensionContext context) {
        testClass = context.getRequiredTestClass();

        AddConfig[] configs = testClass.getAnnotationsByType(AddConfig.class);
        classLevelConfigMeta.addConfig(configs);
        classLevelConfigMeta.configuration(testClass.getAnnotation(Configuration.class));
        configProviderResolver = ConfigProviderResolver.instance();

        AddExtension[] extensions = testClass.getAnnotationsByType(AddExtension.class);
        classLevelExtensions.addAll(Arrays.asList(extensions));

        AddBean[] beans = testClass.getAnnotationsByType(AddBean.class);
        classLevelBeans.addAll(Arrays.asList(beans));

        HelidonTest testAnnot = testClass.getAnnotation(HelidonTest.class);
        if (testAnnot != null) {
            resetPerTest = testAnnot.resetPerTest();
        }

        DisableDiscovery discovery = testClass.getAnnotation(DisableDiscovery.class);
        if (discovery != null) {
            classLevelDisableDiscovery = discovery.value();
        }

        if (resetPerTest) {
            validatePerTest();

            return;
        }
        validatePerClass();

        configure(classLevelConfigMeta);

        startContainer(classLevelBeans, classLevelExtensions, classLevelDisableDiscovery);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (resetPerTest) {
            Method method = context.getRequiredTestMethod();
            AddConfig[] configs = method.getAnnotationsByType(AddConfig.class);
            ConfigMeta methodLevelConfigMeta = classLevelConfigMeta.nextMethod();
            methodLevelConfigMeta.addConfig(configs);
            methodLevelConfigMeta.configuration(method.getAnnotation(Configuration.class));

            configure(methodLevelConfigMeta);

            List<AddExtension> methodLevelExtensions = new ArrayList<>(classLevelExtensions);
            List<AddBean> methodLevelBeans = new ArrayList<>(classLevelBeans);
            boolean methodLevelDisableDiscovery = classLevelDisableDiscovery;

            AddExtension[] extensions = method.getAnnotationsByType(AddExtension.class);
            methodLevelExtensions.addAll(Arrays.asList(extensions));

            AddBean[] beans = method.getAnnotationsByType(AddBean.class);
            methodLevelBeans.addAll(Arrays.asList(beans));

            DisableDiscovery discovery = method.getAnnotation(DisableDiscovery.class);
            if (discovery != null) {
                methodLevelDisableDiscovery = discovery.value();
            }

            startContainer(methodLevelBeans, methodLevelExtensions, methodLevelDisableDiscovery);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (resetPerTest) {
            releaseConfig();
            stopContainer();
        }
    }

    private void validatePerClass() {
        Method[] methods = testClass.getMethods();
        for (Method method : methods) {
            if (method.getAnnotation(Test.class) != null) {
                // a test method
                if (hasHelidonTestAnnotation(method)) {
                    throw new RuntimeException("When a class is annotated with @HelidonTest, "
                                                       + "there is a single CDI container used to invoke all "
                                                       + "test methods on the class. Method " + method
                                                       + " has an annotation that modifies container behavior.");
                }
            }
        }

        methods = testClass.getDeclaredMethods();
        for (Method method : methods) {
            if (method.getAnnotation(Test.class) != null) {
                // a test method
                if (hasHelidonTestAnnotation(method)) {
                    throw new RuntimeException("When a class is annotated with @HelidonTest, "
                                                       + "there is a single CDI container used to invoke all "
                                                       + "test methods on the class. Method " + method
                                                       + " has an annotation that modifies container behavior.");
                }
            }
        }
    }

    private boolean hasHelidonTestAnnotation(AnnotatedElement element) {
        for (Class<? extends Annotation> aClass : HELIDON_TEST_ANNOTATIONS) {
            if (element.getAnnotation(aClass) != null) {
                return true;
            }
        }
        return false;
    }

    private void validatePerTest() {
        Constructor<?>[] constructors = testClass.getConstructors();
        if (constructors.length > 1) {
            throw new RuntimeException("When a class is annotated with @HelidonTest(resetPerTest=true),"
                                               + " the class must have only a single no-arg constructor");
        }
        if (constructors.length == 1) {
            Constructor<?> c = constructors[0];
            if (c.getParameterCount() > 0) {
                throw new RuntimeException("When a class is annotated with @HelidonTest(resetPerTest=true),"
                                                   + " the class must have a no-arg constructor");
            }
        }

        Field[] fields = testClass.getFields();
        for (Field field : fields) {
            if (field.getAnnotation(Inject.class) != null) {
                throw new RuntimeException("When a class is annotated with @HelidonTest(resetPerTest=true),"
                                                   + " injection into fields or constructor is not supported, as each"
                                                   + " test method uses a different CDI container. Field " + field
                                                   + " is annotated with @Inject");
            }
        }

        fields = testClass.getDeclaredFields();
        for (Field field : fields) {
            if (field.getAnnotation(Inject.class) != null) {
                throw new RuntimeException("When a class is annotated with @HelidonTest(resetPerTest=true),"
                                                   + " injection into fields or constructor is not supported, as each"
                                                   + " test method uses a different CDI container. Field " + field
                                                   + " is annotated with @Inject");
            }
        }
    }

    private void configure(ConfigMeta configMeta) {
        if (config != null) {
            configProviderResolver.releaseConfig(config);
        }
        if (!configMeta.useExisting) {
            // only create a custom configuration if not provided by test method/class
            // prepare configuration
            ConfigBuilder builder = configProviderResolver.getBuilder();

            configMeta.additionalSources.forEach(it -> {
                builder.withSources(MpConfigSources.classPath(it).toArray(new ConfigSource[0]));
            });

            config = builder
                    .withSources(MpConfigSources.create(configMeta.additionalKeys))
                    .addDefaultSources()
                    .addDiscoveredSources()
                    .addDiscoveredConverters()
                    .build();
            configProviderResolver.registerConfig(config, Thread.currentThread().getContextClassLoader());
        }
    }

    private void releaseConfig() {
        if (configProviderResolver != null && config != null) {
            configProviderResolver.releaseConfig(config);
            config = null;
        }
    }

    @SuppressWarnings("unchecked")
    private void startContainer(List<AddBean> beanAnnotations,
                                List<AddExtension> extensionAnnotations,
                                boolean disableDiscovery) {

        // now let's prepare the CDI bootstrapping
        SeContainerInitializer initializer = SeContainerInitializer.newInstance();

        if (disableDiscovery) {
            initializer.disableDiscovery();
        }

        initializer.addExtensions(new AddBeansExtension(testClass, beanAnnotations));

        for (AddExtension addExtension : extensionAnnotations) {
            Class<? extends Extension> extensionClass = addExtension.value();
            if (Modifier.isPublic(extensionClass.getModifiers())) {
                initializer.addExtensions(addExtension.value());
            } else {
                throw new IllegalArgumentException("Extension classes must be public, but " + extensionClass
                        .getName() + " is not");
            }
        }

        container = initializer.initialize();
    }

    private void stopContainer() {
        if (container != null) {
            container.close();
            container = null;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        stopContainer();
        releaseConfig();
    }

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
                                               ReflectiveInvocationContext<Constructor<T>> invocationContext,
                                               ExtensionContext extensionContext) throws Throwable {

        if (resetPerTest) {
            // Junit creates test instance
            return invocation.proceed();
        }

        // we need to replace instantiation with CDI lookup, to properly injection into fields (and constructors)
        invocation.skip();

        return container.select(invocationContext.getExecutable().getDeclaringClass())
                .get();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {

        Executable executable = parameterContext.getParameter().getDeclaringExecutable();

        if (resetPerTest) {
            if (executable instanceof Constructor) {
                throw new ParameterResolutionException(
                        "When a test class is annotated with @HelidonTest(resetPerMethod=true), constructor must not have "
                                + "parameters.");
            } else if (executable instanceof Method) {
                if (parameterContext.getParameter().getType().equals(SeContainer.class)) {
                    return true;
                }
            }
        }

        if (executable instanceof Constructor) {
            return !container.select(parameterContext.getParameter().getType()).isUnsatisfied();
        }

        return false;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {

        if (parameterContext.getParameter().getType().equals(SeContainer.class)) {
            return container;
        }
        // we return null, as construction of the object is done by CDI
        // for primitive types we must return appropriate primitive default
        Class<?> type = parameterContext.getParameter().getType();
        if (type.isPrimitive()) {
            // a hack to get to default value of a primitive type
            return Array.get(Array.newInstance(type, 1), 0);
        } else {
            return null;
        }
    }

    // this is not registered as a bean - we manually register an instance
    @SuppressWarnings("CdiManagedBeanInconsistencyInspection")
    private static class AddBeansExtension implements Extension {
        private final Class<?> testClass;
        private final List<AddBean> addBeans;

        private AddBeansExtension(Class<?> testClass, List<AddBean> addBeans) {
            this.testClass = testClass;
            this.addBeans = addBeans;
        }

        void registerAddedBeans(@Observes BeforeBeanDiscovery event) {
            event.addAnnotatedType(testClass, "junit-" + testClass.getName())
                    .add(ApplicationScoped.Literal.INSTANCE);

            for (AddBean addBean : addBeans) {
                Annotation scope;
                Class<? extends Annotation> definedScope = addBean.scope();

                if (definedScope.equals(ApplicationScoped.class) || definedScope.equals(Singleton.class)) {
                    scope = ApplicationScoped.Literal.INSTANCE;
                } else if (definedScope.equals(RequestScoped.class)) {
                    scope = RequestScoped.Literal.INSTANCE;
                } else {
                    throw new IllegalStateException(
                            "Only Singleton, ApplicationScoped and RequestScoped are allowed in tests. Scope " + definedScope
                                    .getName() + " is not allowed for bean " + addBean.value().getName());
                }

                event.addAnnotatedType(addBean.value(), "junit-" + addBean.value().getName())
                        .add(scope);
            }
        }
    }

    private static final class ConfigMeta {
        private final Map<String, String> additionalKeys = new HashMap<>();
        private final List<String> additionalSources = new ArrayList<>();
        private boolean useExisting;

        private ConfigMeta() {
            additionalKeys.put("mp.initializer.allow", "true");
            additionalKeys.put("mp.initializer.no-warn", "true");
            // higher ordinal then all the defaults, system props and environment variables
            additionalKeys.putIfAbsent(ConfigSource.CONFIG_ORDINAL, "1000");
        }

        private void addConfig(AddConfig[] configs) {
            for (AddConfig config : configs) {
                additionalKeys.put(config.key(), config.value());
            }
        }

        private void configuration(Configuration config) {
            if (config == null) {
                return;
            }
            useExisting = config.useExisting();
            additionalSources.addAll(List.of(config.configSources()));
        }

        public ConfigMeta nextMethod() {
            ConfigMeta methodMeta = new ConfigMeta();

            methodMeta.additionalKeys.putAll(this.additionalKeys);
            methodMeta.additionalSources.addAll(this.additionalSources);
            methodMeta.useExisting = this.useExisting;

            return methodMeta;
        }
    }
}