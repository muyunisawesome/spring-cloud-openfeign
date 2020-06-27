/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import java.util.Map;
import java.util.Objects;

import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 只有在其他bean注入feign client时才会调用getObject()方法
 *
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 */
public class FeignClientFactoryBean implements FactoryBean<Object>, InitializingBean, ApplicationContextAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	private Class<?> type;

	private String name;

	private String url;

	private String contextId;

	private String path;

	private boolean decode404;

	//每个contextId指定的小springContext是否继承spring的Context
	private boolean inheritParentContext = true;

	private ApplicationContext applicationContext;

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(this.contextId, "Context id must be set");
		Assert.hasText(this.name, "Name must be set");
	}

	/**
	 * 创建Feign.Builder
	 */
	//Feign.Builder、FeignLoggerFactory、Encoder、Decoder、Contract、FeignClientConfigurer等
	//都在FeignClientsConfiguration配置类中注入spring
	protected Feign.Builder feign(FeignContext context) {
		//从FeignContext上下文中获取FeignLoggerFactory实例
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		//创建此feignClient的logger
		Logger logger = loggerFactory.create(this.type);

		Feign.Builder builder = get(context, Feign.Builder.class)
			// required values 这些都是必要的值
			.logger(logger)
			.encoder(get(context, Encoder.class))
			.decoder(get(context, Decoder.class))
			.contract(get(context, Contract.class));

		//配置feign，logger.level、，retryer、errorDecoder、option、interceptor、queryMapDecoder
		//如果有配置指定了，则替换，比如encoder、decoder、等
		configureFeign(context, builder);
		//给builder应用自定义的逻辑，这也是最后一步可定制feignClient了
		applyBuildCustomizers(context, builder);

		return builder;
	}

	private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
		Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId, FeignBuilderCustomizer.class);

		if (customizerMap != null) {
			customizerMap.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE).forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
		}
	}

	/**
	 * 根据配置类，配置feign client
	 */
	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		//获取属性配置，这是@ConfigurationProperties注解的类
		FeignClientProperties properties = this.applicationContext.getBean(FeignClientProperties.class);

		//也在FeignClientsConfiguration配置类中注入spring
		FeignClientConfigurer feignClientConfigurer = getOptional(context, FeignClientConfigurer.class);
		//设置inheritParentContext属性
		setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		//如果FeignClientProperties实例不为空，即yml或者properties文件配置了feign.client相关配置
		if (properties != null && inheritParentContext) {
			//如果默认是property文件优先级大
			if (properties.isDefaultToProperties()) {
				//则先配置
				configureUsingConfiguration(context, builder);
				//后使用properties配置
				configureUsingProperties(
					//读取feign.client.config
					//读取feign.client.config.defaultClient
					properties.getConfig().get(properties.getDefaultConfig()), builder);
				//读取feign.client.config.contextId
				configureUsingProperties(properties.getConfig().get(this.contextId), builder);
			} else { //如果不是property文件优先级大
				//则先使用properties配置
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				configureUsingProperties(properties.getConfig().get(this.contextId), builder);
				//后使用配置
				configureUsingConfiguration(context, builder);
			}
		} else {
			configureUsingConfiguration(context, builder);
		}
	}

	protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
		Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		} else {
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(this.type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}
		Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
		if (options != null) {
			builder.options(options);
		}
		//多个拦截器
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(context, RequestInterceptor.class);
		if (requestInterceptors != null) {
			builder.requestInterceptors(requestInterceptors.values());
		}
		QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
		if (this.decode404) {
			builder.decode404();
		}
		ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(context, ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}
	}

	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config, Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		if (config.getConnectTimeout() != null && config.getReadTimeout() != null) {
			builder.options(new Request.Options(config.getConnectTimeout(), config.getReadTimeout()));
		}

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return this.applicationContext.getBean(tClass);
		} catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	//--------------------
	/**
	 * 从Feign上下文中此contextId指定的上下文中获取type指定类型的实例<br>
	 * Feign上下文是个上下文工厂, 每个name指定一个上下文<br>
	 *     这个必须有值
	 */
	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(this.contextId, type);
		if (instance == null) {
			throw new IllegalStateException("No bean found of type " + type + " for " + this.contextId);
		}
		return instance;
	}

	/**这个不一定有值*/
	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(this.contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		//如果继承spring
		if (inheritParentContext) {
			return getOptional(context, type);
		} else {
			//否则只从自己的context获取
			return context.getInstanceWithoutAncestors(this.contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(this.contextId, type);
		} else {
			return context.getInstancesWithoutAncestors(this.contextId, type);
		}
	}
	//--------------------


	protected <T> T loadBalance(Feign.Builder builder, FeignContext context, HardCodedTarget<T> target) {
		Client client = getOptional(context, Client.class);
		if (client != null) {
			builder.client(client);
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException("No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?");
	}

	@Override
	public Object getObject() {
		return getTarget();
	}

	/**
	 * @param <T> the target type of the Feign client也就是定义的具体的接口
	 * @return a {@link Feign} client created with the specified data and the context
	 * information
	 */
	<T> T getTarget() {
		//FeignContext在FeignAutoConfiguration配置类中注入spring，所以这里可以直接获取
		FeignContext context = this.applicationContext.getBean(FeignContext.class);
		//配置feign 的decoder、encoder、retryer、contract、RequestInterceptor等
		//这些有默认配置，在FeignAutoConfiguration及FeignClientsConfiguration中有默认配置
		Feign.Builder builder = feign(context);

		//如果url属性没有值
		if (!StringUtils.hasText(this.url)) {
			//url没有值，则用name，需加http开头
			if (!this.name.startsWith("http")) {
				this.url = "http://" + this.name;
			} else {
				this.url = this.name;
			}
			this.url += cleanPath();
			return (T) loadBalance(builder, context, new HardCodedTarget<>(this.type, this.name, this.url));
		}
		//如果url属性有值
		//如果没有http开头，给他加上开头
		if (StringUtils.hasText(this.url) && !this.url.startsWith("http")) {
			this.url = "http://" + this.url;
		}
		//这段代码果然是两个人写的
		String url = this.url + cleanPath();
		Client client = getOptional(context, Client.class); //FeignAutoConfiguration中注入spring。
		//DefaultFeignLoadBalancerConfiguration配置类中，配置默认FeignBlockingLoadBalancerClient
		if (client != null) {
			if (client instanceof FeignBlockingLoadBalancerClient) { //与loadBalance()方法比，只多了这一行
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
			}
			builder.client(client);
		}
		//FeignAutoConfiguration中注入spring
		Targeter targeter = get(context, Targeter.class);
		return (T) targeter.target(this, builder, context, new HardCodedTarget<>(this.type, this.name, url));
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return this.type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return this.contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return this.path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return this.decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public ApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		this.applicationContext = context;
	}

	public Class<?> getFallback() {
		return this.fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return this.fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(this.applicationContext, that.applicationContext) && this.decode404 == that.decode404 && this.inheritParentContext == that.inheritParentContext && Objects.equals(this.fallback, that.fallback) && Objects.equals(this.fallbackFactory, that.fallbackFactory) && Objects.equals(this.name, that.name) && Objects.equals(this.path, that.path) && Objects.equals(this.type, that.type) && Objects.equals(this.url, that.url);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.applicationContext, this.decode404, this.inheritParentContext, this.fallback, this.fallbackFactory, this.name, this.path, this.type, this.url);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(this.type).append(", ").append("name='").append(this.name).append("', ").append("url='").append(this.url).append("', ").append("path='").append(this.path).append("', ").append("decode404=").append(this.decode404).append(", ").append("inheritParentContext=").append(this.inheritParentContext).append(", ").append("applicationContext=").append(this.applicationContext).append(", ").append("fallback=").append(this.fallback).append(", ").append("fallbackFactory=").append(this.fallbackFactory).append("}").toString();
	}

}
