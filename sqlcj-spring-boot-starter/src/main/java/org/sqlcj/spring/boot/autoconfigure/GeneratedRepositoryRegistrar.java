package org.sqlcj.spring.boot.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.sqlcj.runtime.GeneratedRepository;
import org.sqlcj.runtime.QueryExecutor;

import java.util.List;
import java.util.Objects;

/**
 * Registers the classes sqlcj generated in the application's
 * auto-configuration packages as singleton beans built from its one
 * {@link QueryExecutor}.
 *
 * <p>A generated repository carries {@link GeneratedRepository} and takes its
 * executor as its only constructor argument, so each marked class found by
 * Spring's own class-path scan is registered under its fully qualified class
 * name with that executor as its constructor argument. The executor is
 * referenced by name, so registration works over the starter's executor and
 * over an application's own alike.
 *
 * <p>Registration is skipped for a type an existing bean definition already
 * produces, which leaves an application's own declaration of a generated
 * repository in sole possession of that type. Nothing at all is registered
 * unless exactly one {@code QueryExecutor} bean definition exists, counting
 * primary, fallback, and non-default candidates alike, so that an application
 * with two databases never has its repositories wired silently to the wrong
 * executor, and nothing is registered when no auto-configuration package is
 * registered.
 *
 * <p>Being a {@link BeanDefinitionRegistryPostProcessor} declared as a static
 * bean, it runs once every configuration class, {@code @Bean} method, and
 * registrar has been loaded, so both the executor count and what the
 * application already defines are judged on the complete set of bean
 * definitions.
 */
@NullMarked
final class GeneratedRepositoryRegistrar
    implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, EnvironmentAware, ResourceLoaderAware {

    /*
     * The three collaborators arrive through the Aware callbacks, which the
     * container runs before any bean factory post-processing, so each field is
     * null only before the registrar is usable at all.
     */

    private @Nullable BeanFactory beanFactory;

    private @Nullable Environment environment;

    private @Nullable ResourceLoader resourceLoader;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        BeanFactory factory = Objects.requireNonNull(beanFactory);
        if (!AutoConfigurationPackages.has(factory)) {
            return;
        }
        String[] executorNames = beanNamesForType(QueryExecutor.class);
        if (executorNames.length != 1) {
            return;
        }
        List<String> packages = AutoConfigurationPackages.get(factory);
        ClassPathScanningCandidateComponentProvider scanner = markedRepositoryScanner();
        for (String basePackage : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                register(registry, candidate, executorNames[0]);
            }
        }
    }

    private ClassPathScanningCandidateComponentProvider markedRepositoryScanner() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(Objects.requireNonNull(environment));
        scanner.setResourceLoader(Objects.requireNonNull(resourceLoader));
        scanner.addIncludeFilter(new AnnotationTypeFilter(GeneratedRepository.class));
        return scanner;
    }

    /**
     * Registers one scanned repository unless its type is already produced by
     * a bean definition, which also makes overlapping auto-configuration
     * packages register each class once.
     */
    private void register(BeanDefinitionRegistry registry, BeanDefinition candidate, String executorName) {
        Class<?> type = ClassUtils.resolveClassName(
            Objects.requireNonNull(candidate.getBeanClassName()),
            Objects.requireNonNull(resourceLoader).getClassLoader()
        );
        if (beanNamesForType(type).length > 0) {
            return;
        }
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.getConstructorArgumentValues()
            .addIndexedArgumentValue(0, new RuntimeBeanReference(executorName));
        registry.registerBeanDefinition(type.getName(), definition);
    }

    /**
     * Names every bean definition of the type without instantiating a factory
     * bean, so that a repository and the executor are both counted before any
     * bean is created.
     */
    private String[] beanNamesForType(Class<?> type) {
        return ((ListableBeanFactory) Objects.requireNonNull(beanFactory)).getBeanNamesForType(type, true, false);
    }
}
