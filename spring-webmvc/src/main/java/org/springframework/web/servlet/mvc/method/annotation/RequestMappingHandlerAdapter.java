/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.mvc.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.ui.ModelMap;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.support.DefaultDataBinderFactory;
import org.springframework.web.bind.support.DefaultSessionAttributeStore;
import org.springframework.web.bind.support.SessionAttributeStore;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ErrorsMethodArgumentResolver;
import org.springframework.web.method.annotation.ExpressionValueMethodArgumentResolver;
import org.springframework.web.method.annotation.InitBinderDataBinderFactory;
import org.springframework.web.method.annotation.MapMethodProcessor;
import org.springframework.web.method.annotation.ModelAttributeMethodProcessor;
import org.springframework.web.method.annotation.ModelFactory;
import org.springframework.web.method.annotation.ModelMethodProcessor;
import org.springframework.web.method.annotation.RequestHeaderMapMethodArgumentResolver;
import org.springframework.web.method.annotation.RequestHeaderMethodArgumentResolver;
import org.springframework.web.method.annotation.RequestParamMapMethodArgumentResolver;
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver;
import org.springframework.web.method.annotation.SessionAttributesHandler;
import org.springframework.web.method.annotation.SessionStatusMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.mvc.annotation.ModelAndViewResolver;
import org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.WebUtils;

/**
 * Extension of {@link AbstractHandlerMethodAdapter} that supports
 * {@link RequestMapping} annotated {@code HandlerMethod RequestMapping} annotated {@code HandlerMethods}.
 *
 * <p>Support for custom argument and return value types can be added via
 * {@link #setCustomArgumentResolvers} and {@link #setCustomReturnValueHandlers},
 * or alternatively, to re-configure all argument and return value types,
 * use {@link #setArgumentResolvers} and {@link #setReturnValueHandlers}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 * @see HandlerMethodArgumentResolver
 * @see HandlerMethodReturnValueHandler
 * 针对 HandelrMethod 进行处理 并生成 ModelAndView 对象
 */
public class RequestMappingHandlerAdapter extends AbstractHandlerMethodAdapter
		implements BeanFactoryAware, InitializingBean {

	/**
	 * MethodFilter that matches {@link InitBinder @InitBinder} methods.
	 * 拦截 携带 @InitBinder 注解的方法
	 */
	public static final MethodFilter INIT_BINDER_METHODS = method ->
			AnnotatedElementUtils.hasAnnotation(method, InitBinder.class);

	/**
	 * MethodFilter that matches {@link ModelAttribute @ModelAttribute} methods.
	 * 拦截 不携带 @RequestMapping 且携带 @ModelAttribute 注解的方法
	 */
	public static final MethodFilter MODEL_ATTRIBUTE_METHODS = method ->
			(!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class) &&
					AnnotatedElementUtils.hasAnnotation(method, ModelAttribute.class));


	/**
	 * HandlerMethodArgumentResolver 参数解析器接口 从各种能获取到参数的地方收集参数 并设置 到HandlerMethod 的 method 方法中保证正常处理
	 */
	@Nullable
	private List<HandlerMethodArgumentResolver> customArgumentResolvers;

	/**
	 * 内部 维护了一个 HandlerMethodArgumentResolver 列表  用于解析处理器方法需要的参数 以及 添加了@ModelAttribute 注解的方法
	 */
	@Nullable
	private HandlerMethodArgumentResolverComposite argumentResolvers;

	/**
	 * 针对 添加了 @InitBinder 注解的方法参数
	 */
	@Nullable
	private HandlerMethodArgumentResolverComposite initBinderArgumentResolvers;

	@Nullable
	private List<HandlerMethodReturnValueHandler> customReturnValueHandlers;

	/**
	 * 将结果加工成 ModelAndView
	 */
	@Nullable
	private HandlerMethodReturnValueHandlerComposite returnValueHandlers;

	/**
	 * 视图解析器
	 */
	@Nullable
	private List<ModelAndViewResolver> modelAndViewResolvers;

	/**
	 * 内容导航管理器
	 */
	private ContentNegotiationManager contentNegotiationManager = new ContentNegotiationManager();

	private List<HttpMessageConverter<?>> messageConverters;

	/**
	 * 存放了 实现了 RequestAdvice 或 ResponseAdvice 接口的类
 	 */
	private List<Object> requestResponseBodyAdvice = new ArrayList<>();

	@Nullable
	private WebBindingInitializer webBindingInitializer;

	private AsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("MvcAsync");

	@Nullable
	private Long asyncRequestTimeout;

	private CallableProcessingInterceptor[] callableInterceptors = new CallableProcessingInterceptor[0];

	private DeferredResultProcessingInterceptor[] deferredResultInterceptors = new DeferredResultProcessingInterceptor[0];

	private ReactiveAdapterRegistry reactiveAdapterRegistry = ReactiveAdapterRegistry.getSharedInstance();

	private boolean ignoreDefaultModelOnRedirect = false;

	private int cacheSecondsForSessionAttributeHandlers = 0;

	private boolean synchronizeOnSession = false;

	/**
	 * 内部实际就是直接操作 传入的req   这个 req 不是tomcat 的req 而是mvc 框架自己封装的req
	 */
	private SessionAttributeStore sessionAttributeStore = new DefaultSessionAttributeStore();

	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	@Nullable
	private ConfigurableBeanFactory beanFactory;


	private final Map<Class<?>, SessionAttributesHandler> sessionAttributesHandlerCache = new ConcurrentHashMap<>(64);

	private final Map<Class<?>, Set<Method>> initBinderCache = new ConcurrentHashMap<>(64);

	private final Map<ControllerAdviceBean, Set<Method>> initBinderAdviceCache = new LinkedHashMap<>();

	private final Map<Class<?>, Set<Method>> modelAttributeCache = new ConcurrentHashMap<>(64);

	private final Map<ControllerAdviceBean, Set<Method>> modelAttributeAdviceCache = new LinkedHashMap<>();


	public RequestMappingHandlerAdapter() {
		StringHttpMessageConverter stringHttpMessageConverter = new StringHttpMessageConverter();
		stringHttpMessageConverter.setWriteAcceptCharset(false);  // see SPR-7316

		this.messageConverters = new ArrayList<>(4);
		this.messageConverters.add(new ByteArrayHttpMessageConverter());
		this.messageConverters.add(stringHttpMessageConverter);
		try {
			this.messageConverters.add(new SourceHttpMessageConverter<>());
		}
		catch (Error err) {
			// Ignore when no TransformerFactory implementation is available
		}
		this.messageConverters.add(new AllEncompassingFormHttpMessageConverter());
	}


	/**
	 * Provide resolvers for custom argument types. Custom resolvers are ordered
	 * after built-in ones. To override the built-in support for argument
	 * resolution use {@link #setArgumentResolvers} instead.
	 */
	public void setCustomArgumentResolvers(@Nullable List<HandlerMethodArgumentResolver> argumentResolvers) {
		this.customArgumentResolvers = argumentResolvers;
	}

	/**
	 * Return the custom argument resolvers, or {@code null}.
	 */
	@Nullable
	public List<HandlerMethodArgumentResolver> getCustomArgumentResolvers() {
		return this.customArgumentResolvers;
	}

	/**
	 * Configure the complete list of supported argument types thus overriding
	 * the resolvers that would otherwise be configured by default.
	 */
	public void setArgumentResolvers(@Nullable List<HandlerMethodArgumentResolver> argumentResolvers) {
		if (argumentResolvers == null) {
			this.argumentResolvers = null;
		}
		else {
			this.argumentResolvers = new HandlerMethodArgumentResolverComposite();
			this.argumentResolvers.addResolvers(argumentResolvers);
		}
	}

	/**
	 * Return the configured argument resolvers, or possibly {@code null} if
	 * not initialized yet via {@link #afterPropertiesSet()}.
	 */
	@Nullable
	public List<HandlerMethodArgumentResolver> getArgumentResolvers() {
		return (this.argumentResolvers != null ? this.argumentResolvers.getResolvers() : null);
	}

	/**
	 * Configure the supported argument types in {@code @InitBinder} methods.
	 */
	public void setInitBinderArgumentResolvers(@Nullable List<HandlerMethodArgumentResolver> argumentResolvers) {
		if (argumentResolvers == null) {
			this.initBinderArgumentResolvers = null;
		}
		else {
			this.initBinderArgumentResolvers = new HandlerMethodArgumentResolverComposite();
			this.initBinderArgumentResolvers.addResolvers(argumentResolvers);
		}
	}

	/**
	 * Return the argument resolvers for {@code @InitBinder} methods, or possibly
	 * {@code null} if not initialized yet via {@link #afterPropertiesSet()}.
	 */
	@Nullable
	public List<HandlerMethodArgumentResolver> getInitBinderArgumentResolvers() {
		return (this.initBinderArgumentResolvers != null ? this.initBinderArgumentResolvers.getResolvers() : null);
	}

	/**
	 * Provide handlers for custom return value types. Custom handlers are
	 * ordered after built-in ones. To override the built-in support for
	 * return value handling use {@link #setReturnValueHandlers}.
	 */
	public void setCustomReturnValueHandlers(@Nullable List<HandlerMethodReturnValueHandler> returnValueHandlers) {
		this.customReturnValueHandlers = returnValueHandlers;
	}

	/**
	 * Return the custom return value handlers, or {@code null}.
	 */
	@Nullable
	public List<HandlerMethodReturnValueHandler> getCustomReturnValueHandlers() {
		return this.customReturnValueHandlers;
	}

	/**
	 * Configure the complete list of supported return value types thus
	 * overriding handlers that would otherwise be configured by default.
	 */
	public void setReturnValueHandlers(@Nullable List<HandlerMethodReturnValueHandler> returnValueHandlers) {
		if (returnValueHandlers == null) {
			this.returnValueHandlers = null;
		}
		else {
			this.returnValueHandlers = new HandlerMethodReturnValueHandlerComposite();
			this.returnValueHandlers.addHandlers(returnValueHandlers);
		}
	}

	/**
	 * Return the configured handlers, or possibly {@code null} if not
	 * initialized yet via {@link #afterPropertiesSet()}.
	 */
	@Nullable
	public List<HandlerMethodReturnValueHandler> getReturnValueHandlers() {
		return (this.returnValueHandlers != null ? this.returnValueHandlers.getHandlers() : null);
	}

	/**
	 * Provide custom {@link ModelAndViewResolver ModelAndViewResolvers}.
	 * <p><strong>Note:</strong> This method is available for backwards
	 * compatibility only. However, it is recommended to re-write a
	 * {@code ModelAndViewResolver} as {@link HandlerMethodReturnValueHandler}.
	 * An adapter between the two interfaces is not possible since the
	 * {@link HandlerMethodReturnValueHandler#supportsReturnType} method
	 * cannot be implemented. Hence {@code ModelAndViewResolver}s are limited
	 * to always being invoked at the end after all other return value
	 * handlers have been given a chance.
	 * <p>A {@code HandlerMethodReturnValueHandler} provides better access to
	 * the return type and controller method information and can be ordered
	 * freely relative to other return value handlers.
	 */
	public void setModelAndViewResolvers(@Nullable List<ModelAndViewResolver> modelAndViewResolvers) {
		this.modelAndViewResolvers = modelAndViewResolvers;
	}

	/**
	 * Return the configured {@link ModelAndViewResolver ModelAndViewResolvers}, or {@code null}.
	 */
	@Nullable
	public List<ModelAndViewResolver> getModelAndViewResolvers() {
		return this.modelAndViewResolvers;
	}

	/**
	 * Set the {@link ContentNegotiationManager} to use to determine requested media types.
	 * If not set, the default constructor is used.
	 */
	public void setContentNegotiationManager(ContentNegotiationManager contentNegotiationManager) {
		this.contentNegotiationManager = contentNegotiationManager;
	}

	/**
	 * Provide the converters to use in argument resolvers and return value
	 * handlers that support reading and/or writing to the body of the
	 * request and response.
	 */
	public void setMessageConverters(List<HttpMessageConverter<?>> messageConverters) {
		this.messageConverters = messageConverters;
	}

	/**
	 * Return the configured message body converters.
	 */
	public List<HttpMessageConverter<?>> getMessageConverters() {
		return this.messageConverters;
	}

	/**
	 * Add one or more {@code RequestBodyAdvice} instances to intercept the
	 * request before it is read and converted for {@code @RequestBody} and
	 * {@code HttpEntity} method arguments.
	 */
	public void setRequestBodyAdvice(@Nullable List<RequestBodyAdvice> requestBodyAdvice) {
		if (requestBodyAdvice != null) {
			this.requestResponseBodyAdvice.addAll(requestBodyAdvice);
		}
	}

	/**
	 * Add one or more {@code ResponseBodyAdvice} instances to intercept the
	 * response before {@code @ResponseBody} or {@code ResponseEntity} return
	 * values are written to the response body.
	 */
	public void setResponseBodyAdvice(@Nullable List<ResponseBodyAdvice<?>> responseBodyAdvice) {
		if (responseBodyAdvice != null) {
			this.requestResponseBodyAdvice.addAll(responseBodyAdvice);
		}
	}

	/**
	 * Provide a WebBindingInitializer with "global" initialization to apply
	 * to every DataBinder instance.
	 */
	public void setWebBindingInitializer(@Nullable WebBindingInitializer webBindingInitializer) {
		this.webBindingInitializer = webBindingInitializer;
	}

	/**
	 * Return the configured WebBindingInitializer, or {@code null} if none.
	 */
	@Nullable
	public WebBindingInitializer getWebBindingInitializer() {
		return this.webBindingInitializer;
	}

	/**
	 * Set the default {@link AsyncTaskExecutor} to use when a controller method
	 * return a {@link Callable}. Controller methods can override this default on
	 * a per-request basis by returning an {@link WebAsyncTask}.
	 * <p>By default a {@link SimpleAsyncTaskExecutor} instance is used.
	 * It's recommended to change that default in production as the simple executor
	 * does not re-use threads.
	 */
	public void setTaskExecutor(AsyncTaskExecutor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Specify the amount of time, in milliseconds, before concurrent handling
	 * should time out. In Servlet 3, the timeout begins after the main request
	 * processing thread has exited and ends when the request is dispatched again
	 * for further processing of the concurrently produced result.
	 * <p>If this value is not set, the default timeout of the underlying
	 * implementation is used, e.g. 10 seconds on Tomcat with Servlet 3.
	 * @param timeout the timeout value in milliseconds
	 */
	public void setAsyncRequestTimeout(long timeout) {
		this.asyncRequestTimeout = timeout;
	}

	/**
	 * Configure {@code CallableProcessingInterceptor}'s to register on async requests.
	 * @param interceptors the interceptors to register
	 */
	public void setCallableInterceptors(List<CallableProcessingInterceptor> interceptors) {
		this.callableInterceptors = interceptors.toArray(new CallableProcessingInterceptor[0]);
	}

	/**
	 * Configure {@code DeferredResultProcessingInterceptor}'s to register on async requests.
	 * @param interceptors the interceptors to register
	 */
	public void setDeferredResultInterceptors(List<DeferredResultProcessingInterceptor> interceptors) {
		this.deferredResultInterceptors = interceptors.toArray(new DeferredResultProcessingInterceptor[0]);
	}

	/**
	 * Configure the registry for reactive library types to be supported as
	 * return values from controller methods.
	 * @since 5.0.5
	 */
	public void setReactiveAdapterRegistry(ReactiveAdapterRegistry reactiveAdapterRegistry) {
		this.reactiveAdapterRegistry = reactiveAdapterRegistry;
	}

	/**
	 * Return the configured reactive type registry of adapters.
	 * @since 5.0
	 */
	public ReactiveAdapterRegistry getReactiveAdapterRegistry() {
		return this.reactiveAdapterRegistry;
	}

	/**
	 * By default the content of the "default" model is used both during
	 * rendering and redirect scenarios. Alternatively a controller method
	 * can declare a {@link RedirectAttributes} argument and use it to provide
	 * attributes for a redirect.
	 * <p>Setting this flag to {@code true} guarantees the "default" model is
	 * never used in a redirect scenario even if a RedirectAttributes argument
	 * is not declared. Setting it to {@code false} means the "default" model
	 * may be used in a redirect if the controller method doesn't declare a
	 * RedirectAttributes argument.
	 * <p>The default setting is {@code false} but new applications should
	 * consider setting it to {@code true}.
	 * @see RedirectAttributes
	 */
	public void setIgnoreDefaultModelOnRedirect(boolean ignoreDefaultModelOnRedirect) {
		this.ignoreDefaultModelOnRedirect = ignoreDefaultModelOnRedirect;
	}

	/**
	 * Specify the strategy to store session attributes with. The default is
	 * {@link org.springframework.web.bind.support.DefaultSessionAttributeStore},
	 * storing session attributes in the HttpSession with the same attribute
	 * name as in the model.
	 */
	public void setSessionAttributeStore(SessionAttributeStore sessionAttributeStore) {
		this.sessionAttributeStore = sessionAttributeStore;
	}

	/**
	 * Cache content produced by {@code @SessionAttributes} annotated handlers
	 * for the given number of seconds.
	 * <p>Possible values are:
	 * <ul>
	 * <li>-1: no generation of cache-related headers</li>
	 * <li>0 (default value): "Cache-Control: no-store" will prevent caching</li>
	 * <li>1 or higher: "Cache-Control: max-age=seconds" will ask to cache content;
	 * not advised when dealing with session attributes</li>
	 * </ul>
	 * <p>In contrast to the "cacheSeconds" property which will apply to all general
	 * handlers (but not to {@code @SessionAttributes} annotated handlers),
	 * this setting will apply to {@code @SessionAttributes} handlers only.
	 * @see #setCacheSeconds
	 * @see org.springframework.web.bind.annotation.SessionAttributes
	 */
	public void setCacheSecondsForSessionAttributeHandlers(int cacheSecondsForSessionAttributeHandlers) {
		this.cacheSecondsForSessionAttributeHandlers = cacheSecondsForSessionAttributeHandlers;
	}

	/**
	 * Set if controller execution should be synchronized on the session,
	 * to serialize parallel invocations from the same client.
	 * <p>More specifically, the execution of the {@code handleRequestInternal}
	 * method will get synchronized if this flag is "true". The best available
	 * session mutex will be used for the synchronization; ideally, this will
	 * be a mutex exposed by HttpSessionMutexListener.
	 * <p>The session mutex is guaranteed to be the same object during
	 * the entire lifetime of the session, available under the key defined
	 * by the {@code SESSION_MUTEX_ATTRIBUTE} constant. It serves as a
	 * safe reference to synchronize on for locking on the current session.
	 * <p>In many cases, the HttpSession reference itself is a safe mutex
	 * as well, since it will always be the same object reference for the
	 * same active logical session. However, this is not guaranteed across
	 * different servlet containers; the only 100% safe way is a session mutex.
	 * @see org.springframework.web.util.HttpSessionMutexListener
	 * @see org.springframework.web.util.WebUtils#getSessionMutex(javax.servlet.http.HttpSession)
	 */
	public void setSynchronizeOnSession(boolean synchronizeOnSession) {
		this.synchronizeOnSession = synchronizeOnSession;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter names if needed
	 * (e.g. for default attribute names).
	 * <p>Default is a {@link org.springframework.core.DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * A {@link ConfigurableBeanFactory} is expected for resolving expressions
	 * in method argument default values.
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (beanFactory instanceof ConfigurableBeanFactory) {
			this.beanFactory = (ConfigurableBeanFactory) beanFactory;
		}
	}

	/**
	 * Return the owning factory of this bean instance, or {@code null} if none.
	 */
	@Nullable
	protected ConfigurableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}


	/**
	 * 当对象初始化 完成 并设置属性后调用
	 */
	@Override
	public void afterPropertiesSet() {
		// Do this first, it may add ResponseBody advice beans
		// 从 beanFactory 中找到 ControllerAdvice 注解修饰的 类并查找一系列的方法添加到容器中
		initControllerAdviceCache();

		// ModelAndViewResolver 不是必填的

		if (this.argumentResolvers == null) {
			// 加载bean工厂中默认的 参数解析器 当然也可以定制
			List<HandlerMethodArgumentResolver> resolvers = getDefaultArgumentResolvers();
			this.argumentResolvers = new HandlerMethodArgumentResolverComposite().addResolvers(resolvers);
		}

		// 基本同上

		if (this.initBinderArgumentResolvers == null) {
			List<HandlerMethodArgumentResolver> resolvers = getDefaultInitBinderArgumentResolvers();
			this.initBinderArgumentResolvers = new HandlerMethodArgumentResolverComposite().addResolvers(resolvers);
		}
		if (this.returnValueHandlers == null) {
			List<HandlerMethodReturnValueHandler> handlers = getDefaultReturnValueHandlers();
			this.returnValueHandlers = new HandlerMethodReturnValueHandlerComposite().addHandlers(handlers);
		}
	}

	/*
	 * 初始化 携带 @ControllerAdvice 注解的类 这些类 能对controller 起到增强作用
	 */
	private void initControllerAdviceCache() {
		if (getApplicationContext() == null) {
			return;
		}

		// 抽取出携带 ControllerAdvice 的 bean 的信息
		List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(getApplicationContext());
		AnnotationAwareOrderComparator.sort(adviceBeans);

		List<Object> requestResponseBodyAdviceBeans = new ArrayList<>();

		for (ControllerAdviceBean adviceBean : adviceBeans) {
			Class<?> beanType = adviceBean.getBeanType();
			if (beanType == null) {
				throw new IllegalStateException("Unresolvable type for ControllerAdviceBean: " + adviceBean);
			}
			// 筛选出合适的方法  不携带 @RequestMapping 而 携带@ModelAttribute 注解的方法
			Set<Method> attrMethods = MethodIntrospector.selectMethods(beanType, MODEL_ATTRIBUTE_METHODS);
			if (!attrMethods.isEmpty()) {
				// 因为每个 方法都要用到 advice 所以使用缓存
				this.modelAttributeAdviceCache.put(adviceBean, attrMethods);
			}
			// 找到携带 @InitBinder 注解的方法
			Set<Method> binderMethods = MethodIntrospector.selectMethods(beanType, INIT_BINDER_METHODS);
			if (!binderMethods.isEmpty()) {
				this.initBinderAdviceCache.put(adviceBean, binderMethods);
			}

			// 是否实现了 spring 内置的 advice 接口
			if (RequestBodyAdvice.class.isAssignableFrom(beanType)) {
				requestResponseBodyAdviceBeans.add(adviceBean);
			}
			if (ResponseBodyAdvice.class.isAssignableFrom(beanType)) {
				requestResponseBodyAdviceBeans.add(adviceBean);
			}
		}

		if (!requestResponseBodyAdviceBeans.isEmpty()) {
			this.requestResponseBodyAdvice.addAll(0, requestResponseBodyAdviceBeans);
		}

		if (logger.isDebugEnabled()) {
			int modelSize = this.modelAttributeAdviceCache.size();
			int binderSize = this.initBinderAdviceCache.size();
			int reqCount = getBodyAdviceCount(RequestBodyAdvice.class);
			int resCount = getBodyAdviceCount(ResponseBodyAdvice.class);
			if (modelSize == 0 && binderSize == 0 && reqCount == 0 && resCount == 0) {
				logger.debug("ControllerAdvice beans: none");
			}
			else {
				logger.debug("ControllerAdvice beans: " + modelSize + " @ModelAttribute, " + binderSize +
						" @InitBinder, " + reqCount + " RequestBodyAdvice, " + resCount + " ResponseBodyAdvice");
			}
		}
	}

	// Count all advice, including explicit registrations..

	private int getBodyAdviceCount(Class<?> adviceType) {
		List<Object> advice = this.requestResponseBodyAdvice;
		return RequestBodyAdvice.class.isAssignableFrom(adviceType) ?
				RequestResponseBodyAdviceChain.getAdviceByType(advice, RequestBodyAdvice.class).size() :
				RequestResponseBodyAdviceChain.getAdviceByType(advice, ResponseBodyAdvice.class).size();
	}

	/**
	 * Return the list of argument resolvers to use including built-in resolvers
	 * and custom resolvers provided via {@link #setCustomArgumentResolvers}.
	 * 添加默认的参数解析器
	 */
	private List<HandlerMethodArgumentResolver> getDefaultArgumentResolvers() {
		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

		// Annotation-based argument resolution
		// 按照注解来解析
		resolvers.add(new RequestParamMethodArgumentResolver(getBeanFactory(), false));
		resolvers.add(new RequestParamMapMethodArgumentResolver());
		resolvers.add(new PathVariableMethodArgumentResolver());
		resolvers.add(new PathVariableMapMethodArgumentResolver());
		resolvers.add(new MatrixVariableMethodArgumentResolver());
		resolvers.add(new MatrixVariableMapMethodArgumentResolver());
		resolvers.add(new ServletModelAttributeMethodProcessor(false));
		resolvers.add(new RequestResponseBodyMethodProcessor(getMessageConverters(), this.requestResponseBodyAdvice));
		resolvers.add(new RequestPartMethodArgumentResolver(getMessageConverters(), this.requestResponseBodyAdvice));
		resolvers.add(new RequestHeaderMethodArgumentResolver(getBeanFactory()));
		resolvers.add(new RequestHeaderMapMethodArgumentResolver());
		resolvers.add(new ServletCookieValueMethodArgumentResolver(getBeanFactory()));
		resolvers.add(new ExpressionValueMethodArgumentResolver(getBeanFactory()));
		resolvers.add(new SessionAttributeMethodArgumentResolver());
		resolvers.add(new RequestAttributeMethodArgumentResolver());

		// Type-based argument resolution
		// 按照参数类型来解析
		resolvers.add(new ServletRequestMethodArgumentResolver());
		resolvers.add(new ServletResponseMethodArgumentResolver());
		resolvers.add(new HttpEntityMethodProcessor(getMessageConverters(), this.requestResponseBodyAdvice));
		resolvers.add(new RedirectAttributesMethodArgumentResolver());
		resolvers.add(new ModelMethodProcessor());
		resolvers.add(new MapMethodProcessor());
		resolvers.add(new ErrorsMethodArgumentResolver());
		resolvers.add(new SessionStatusMethodArgumentResolver());
		resolvers.add(new UriComponentsBuilderMethodArgumentResolver());

		// Custom arguments
		if (getCustomArgumentResolvers() != null) {
			resolvers.addAll(getCustomArgumentResolvers());
		}

		// Catch-all
		// 能处理所有的参数解析
		resolvers.add(new RequestParamMethodArgumentResolver(getBeanFactory(), true));
		resolvers.add(new ServletModelAttributeMethodProcessor(true));

		return resolvers;
	}

	/**
	 * Return the list of argument resolvers to use for {@code @InitBinder}
	 * methods including built-in and custom resolvers.
	 */
	private List<HandlerMethodArgumentResolver> getDefaultInitBinderArgumentResolvers() {
		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

		// Annotation-based argument resolution
		resolvers.add(new RequestParamMethodArgumentResolver(getBeanFactory(), false));
		resolvers.add(new RequestParamMapMethodArgumentResolver());
		resolvers.add(new PathVariableMethodArgumentResolver());
		resolvers.add(new PathVariableMapMethodArgumentResolver());
		resolvers.add(new MatrixVariableMethodArgumentResolver());
		resolvers.add(new MatrixVariableMapMethodArgumentResolver());
		resolvers.add(new ExpressionValueMethodArgumentResolver(getBeanFactory()));
		resolvers.add(new SessionAttributeMethodArgumentResolver());
		resolvers.add(new RequestAttributeMethodArgumentResolver());

		// Type-based argument resolution
		resolvers.add(new ServletRequestMethodArgumentResolver());
		resolvers.add(new ServletResponseMethodArgumentResolver());

		// Custom arguments
		if (getCustomArgumentResolvers() != null) {
			resolvers.addAll(getCustomArgumentResolvers());
		}

		// Catch-all
		resolvers.add(new RequestParamMethodArgumentResolver(getBeanFactory(), true));

		return resolvers;
	}

	/**
	 * Return the list of return value handlers to use including built-in and
	 * custom handlers provided via {@link #setReturnValueHandlers}.
	 */
	private List<HandlerMethodReturnValueHandler> getDefaultReturnValueHandlers() {
		List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();

		// Single-purpose return value types
		handlers.add(new ModelAndViewMethodReturnValueHandler());
		handlers.add(new ModelMethodProcessor());
		handlers.add(new ViewMethodReturnValueHandler());
		handlers.add(new ResponseBodyEmitterReturnValueHandler(getMessageConverters(),
				this.reactiveAdapterRegistry, this.taskExecutor, this.contentNegotiationManager));
		handlers.add(new StreamingResponseBodyReturnValueHandler());
		handlers.add(new HttpEntityMethodProcessor(getMessageConverters(),
				this.contentNegotiationManager, this.requestResponseBodyAdvice));
		handlers.add(new HttpHeadersReturnValueHandler());
		handlers.add(new CallableMethodReturnValueHandler());
		handlers.add(new DeferredResultMethodReturnValueHandler());
		handlers.add(new AsyncTaskMethodReturnValueHandler(this.beanFactory));

		// Annotation-based return value types
		handlers.add(new ModelAttributeMethodProcessor(false));
		handlers.add(new RequestResponseBodyMethodProcessor(getMessageConverters(),
				this.contentNegotiationManager, this.requestResponseBodyAdvice));

		// Multi-purpose return value types
		handlers.add(new ViewNameMethodReturnValueHandler());
		handlers.add(new MapMethodProcessor());

		// Custom return value types
		if (getCustomReturnValueHandlers() != null) {
			handlers.addAll(getCustomReturnValueHandlers());
		}

		// Catch-all  存在时 会添加一个 特殊的 returnValueHandler 能够处理所有的返回值
		if (!CollectionUtils.isEmpty(getModelAndViewResolvers())) {
			handlers.add(new ModelAndViewResolverMethodReturnValueHandler(getModelAndViewResolvers()));
		}
		else {
			handlers.add(new ModelAttributeMethodProcessor(true));
		}

		return handlers;
	}


	/**
	 * Always return {@code true} since any method argument and return value
	 * type will be processed in some way. A method argument not recognized
	 * by any HandlerMethodArgumentResolver is interpreted as a request parameter
	 * if it is a simple type, or as a model attribute otherwise. A return value
	 * not recognized by any HandlerMethodReturnValueHandler will be interpreted
	 * as a model attribute.
	 * 没有增加额外的条件 也就是 只要 handler是 HandlerMethod 类型 即符合条件
	 */
	@Override
	protected boolean supportsInternal(HandlerMethod handlerMethod) {
		return true;
	}

	/**
	 * 通过 handler 处理请求 注意这里是返回 ModelAndView的
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param handlerMethod handler method to use. This object must have previously been passed to the
	 * {@link #supportsInternal(HandlerMethod)} this interface, which must have returned {@code true}.
	 * @return
	 * @throws Exception
	 * 传入 req res 以及 handle 方法 处理完后返回 ModelAndView 对象
	 */
	@Override
	protected ModelAndView handleInternal(HttpServletRequest request,
			HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {

		ModelAndView mav;
		// 判断请求方式 是否支持  默认除了 Optional外 全部支持
		checkRequest(request);

		// Execute invokeHandlerMethod in synchronized block if required.
		// 是否要在同步块中执行  一般都是false
		if (this.synchronizeOnSession) {
			// getSession(false) 代表如果没有找到req 关联的 session 不选择新建session 返回null
			HttpSession session = request.getSession(false);
			if (session != null) {
				// 获取session 的互斥锁
				Object mutex = WebUtils.getSessionMutex(session);
				// 在同步块中执行
				synchronized (mutex) {
					// 执行方法并返回 mav 对象
					mav = invokeHandlerMethod(request, response, handlerMethod);
				}
			}
			else {
				// 没有找到session 对象 也就只能不在同步块包裹下 执行
				// No HttpSession available -> no mutex necessary
				mav = invokeHandlerMethod(request, response, handlerMethod);
			}
		}
		else {
			// No synchronization on session demanded at all...
			mav = invokeHandlerMethod(request, response, handlerMethod);
		}

		// 如果没有包含 缓存控制  CacheControl 只是内部存放了一些属性 没有实际的缓存功能
		if (!response.containsHeader(HEADER_CACHE_CONTROL)) {
			// 获取 Controller级别的 @SessionAttribute 注解
			if (getSessionAttributesHandler(handlerMethod).hasSessionAttributes()) {
				// 设置缓存过期时间 这里缓存时间为0  缓存相关的先不看  最后就是在 res 中设置一些响应头
				applyCacheSeconds(response, this.cacheSecondsForSessionAttributeHandlers);
			}
			// 如果@Controller 上没有携带 @SessionAttribute
			else {
				// 缓存 res 同样只是 在 res 中设置了响应头
				prepareResponse(response);
			}
		}

		return mav;
	}

	/**
	 * This implementation always returns -1. An {@code @RequestMapping} method can
	 * calculate the lastModified value, call {@link WebRequest#checkNotModified(long)},
	 * and return {@code null} if the result of that call is {@code true}.
	 */
	@Override
	protected long getLastModifiedInternal(HttpServletRequest request, HandlerMethod handlerMethod) {
		return -1;
	}


	/**
	 * Return the {@link SessionAttributesHandler} instance for the given handler type
	 * (never {@code null}).
	 * 将普通handle 方法变成 SessionAttributeHandler 方法
	 */
	private SessionAttributesHandler getSessionAttributesHandler(HandlerMethod handlerMethod) {
		Class<?> handlerType = handlerMethod.getBeanType();
		// 为了避免每次都查找 使用了缓存  sessionAttributesHandlerCache 内维护的就是 controller 级别的 信息
		SessionAttributesHandler sessionAttrHandler = this.sessionAttributesHandlerCache.get(handlerType);
		if (sessionAttrHandler == null) {
			synchronized (this.sessionAttributesHandlerCache) {
				sessionAttrHandler = this.sessionAttributesHandlerCache.get(handlerType);
				if (sessionAttrHandler == null) {
					// 生成 SessionAttributeHandler 并放入缓存
					sessionAttrHandler = new SessionAttributesHandler(handlerType, this.sessionAttributeStore);
					this.sessionAttributesHandlerCache.put(handlerType, sessionAttrHandler);
				}
			}
		}
		return sessionAttrHandler;
	}

	/**
	 * Invoke the {@link RequestMapping} handler method preparing a {@link ModelAndView}
	 * if view resolution is required.
	 * @since 4.2
	 * @see #createInvocableHandlerMethod(HandlerMethod)
	 * 调用 handlerMethod 处理请求 并返回 ModelAndView 对象
	 */
	@Nullable
	protected ModelAndView invokeHandlerMethod(HttpServletRequest request,
			HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {

		// 将 HttpServletRequest 和 HttpServletResponse 封装成了一个 WebRequest 对象
		ServletWebRequest webRequest = new ServletWebRequest(request, response);
		try {
			// 获取 Controller 以及能作用在该 Controller 上所有的ControllerAdvice 的携带@InitBinder 方法 生成一个工厂对象
			// 这是为了 之后创建 binder 对象时 如果 符合 @InitBinder 就会执行  对应的方法
			WebDataBinderFactory binderFactory = getDataBinderFactory(handlerMethod);
			// modelFactory 用于处理 model 当handler 处理请求时 需要初始化本次的 model 并且处理结束后需要对model 进行更新
			ModelFactory modelFactory = getModelFactory(handlerMethod, binderFactory);

			// 将handle 方法本身生成 ServletInvocableHandlerMethod
			ServletInvocableHandlerMethod invocableMethod = createInvocableHandlerMethod(handlerMethod);
			if (this.argumentResolvers != null) {
				// 设置参数解析器
				invocableMethod.setHandlerMethodArgumentResolvers(this.argumentResolvers);
			}
			if (this.returnValueHandlers != null) {
				// 设置返回值处理器
				invocableMethod.setHandlerMethodReturnValueHandlers(this.returnValueHandlers);
			}
			invocableMethod.setDataBinderFactory(binderFactory);
			invocableMethod.setParameterNameDiscoverer(this.parameterNameDiscoverer);

			// 生成一个 ModelAndViewCOntainer 对象
			ModelAndViewContainer mavContainer = new ModelAndViewContainer();
			// 将 FlashMap 中的全部属性 复制到 mavc 中 (也就是填充到 Model 中)  flashMap 的属性是 什么时候设置的???
			mavContainer.addAllAttributes(RequestContextUtils.getInputFlashMap(request));
			// 初始化 Model 对象  这里会执行 对应@Controller 下所有携带 @ModelAttribute 方法级别注解的方法 生成结果 保存到model 中 还有从req中获取@SessionAttribute 修饰的属性
			modelFactory.initModel(webRequest, mavContainer, invocableMethod);
			// 设置 当使用重定向时 是否忽略 defaultModel 中设置的属性
			mavContainer.setIgnoreDefaultModelOnRedirect(this.ignoreDefaultModelOnRedirect);

			// 创建异步请求对象
			AsyncWebRequest asyncWebRequest = WebAsyncUtils.createAsyncWebRequest(request, response);
			asyncWebRequest.setTimeout(this.asyncRequestTimeout);

			// 获取本请求对象的 异步管理器
			WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
			// 异步处理交由线程池执行
			asyncManager.setTaskExecutor(this.taskExecutor);
			asyncManager.setAsyncWebRequest(asyncWebRequest);
			asyncManager.registerCallableInterceptors(this.callableInterceptors);
			asyncManager.registerDeferredResultInterceptors(this.deferredResultInterceptors);

			// 一开始创建 manager 时 为false 应该是处理完的时候会修改标识
			if (asyncManager.hasConcurrentResult()) {
				Object result = asyncManager.getConcurrentResult();
				mavContainer = (ModelAndViewContainer) asyncManager.getConcurrentResultContext()[0];
				asyncManager.clearConcurrentResult();
				LogFormatUtils.traceDebug(logger, traceOn -> {
					String formatted = LogFormatUtils.formatValue(result, !traceOn);
					return "Resume with async result [" + formatted + "]";
				});
				invocableMethod = invocableMethod.wrapConcurrentResult(result);
			}

			// 执行方法 里面会 调用 argumentResolver 和 returnValueResolver 执行完后 如果携带Model  就会将结果设置到 mavc 中
			invocableMethod.invokeAndHandle(webRequest, mavContainer);
			// 如果是 异步的话 直接返回null
			if (asyncManager.isConcurrentHandlingStarted()) {
				return null;
			}

			return getModelAndView(mavContainer, modelFactory, webRequest);
		}
		finally {
			// 代表本次请求已经处理完了
			webRequest.requestCompleted();
		}
	}

	/**
	 * Create a {@link ServletInvocableHandlerMethod} from the given {@link HandlerMethod} definition.
	 * @param handlerMethod the {@link HandlerMethod} definition
	 * @return the corresponding {@link ServletInvocableHandlerMethod} (or custom subclass thereof)
	 * @since 4.2
	 */
	protected ServletInvocableHandlerMethod createInvocableHandlerMethod(HandlerMethod handlerMethod) {
		return new ServletInvocableHandlerMethod(handlerMethod);
	}

	/**
	 * 构建 Model 工厂  用于初始化 handle 方法中的model 以及结束时 更新model 的值
	 * @param handlerMethod
	 * @param binderFactory
	 * @return
	 */
	private ModelFactory getModelFactory(HandlerMethod handlerMethod, WebDataBinderFactory binderFactory) {
		// 获取该Controller的 @SessionAttribute 信息
		SessionAttributesHandler sessionAttrHandler = getSessionAttributesHandler(handlerMethod);
		// 获取Controller 类型
		Class<?> handlerType = handlerMethod.getBeanType();
		Set<Method> methods = this.modelAttributeCache.get(handlerType);
		if (methods == null) {
			// 寻找携带 @ModelAttribute 的方法
			methods = MethodIntrospector.selectMethods(handlerType, MODEL_ATTRIBUTE_METHODS);
			this.modelAttributeCache.put(handlerType, methods);
		}
		List<InvocableHandlerMethod> attrMethods = new ArrayList<>();
		// Global methods first
		// 先找 @ControllerAdvice 中 同样带有 @ModelAttribute的方法
		this.modelAttributeAdviceCache.forEach((clazz, methodSet) -> {
			if (clazz.isApplicableToBeanType(handlerType)) {
				Object bean = clazz.resolveBean();
				for (Method method : methodSet) {
					attrMethods.add(createModelAttributeMethod(binderFactory, bean, method));
				}
			}
		});
		for (Method method : methods) {
			Object bean = handlerMethod.getBean();
			attrMethods.add(createModelAttributeMethod(binderFactory, bean, method));
		}
		// 生成Model工厂
		return new ModelFactory(attrMethods, binderFactory, sessionAttrHandler);
	}

	/**
	 * 生成 InvocableHandlerMethod 对象
	 * @param factory
	 * @param bean
	 * @param method
	 * @return
	 */
	private InvocableHandlerMethod createModelAttributeMethod(WebDataBinderFactory factory, Object bean, Method method) {
		InvocableHandlerMethod attrMethod = new InvocableHandlerMethod(bean, method);
		if (this.argumentResolvers != null) {
			attrMethod.setHandlerMethodArgumentResolvers(this.argumentResolvers);
		}
		attrMethod.setParameterNameDiscoverer(this.parameterNameDiscoverer);
		attrMethod.setDataBinderFactory(factory);
		return attrMethod;
	}

	/**
	 * 根据传入方法 获取 binder 工厂
	 * @param handlerMethod
	 * @return
	 * @throws Exception
	 */
	private WebDataBinderFactory getDataBinderFactory(HandlerMethod handlerMethod) throws Exception {
		// 获取 Controller 属性
		Class<?> handlerType = handlerMethod.getBeanType();
		Set<Method> methods = this.initBinderCache.get(handlerType);
		if (methods == null) {
			// 首次调用为 null  这里是寻找 该类下 所有携带 @InitBinder 的方法 也就是一个 Controller 下可能有多个方法 携带 @InitBinder 注解
			methods = MethodIntrospector.selectMethods(handlerType, INIT_BINDER_METHODS);
			this.initBinderCache.put(handlerType, methods);
		}
		List<InvocableHandlerMethod> initBinderMethods = new ArrayList<>();
		// Global methods first  先尝试从 @ControllerAdivce 中 获取所有携带@InitBinder 的方法
		this.initBinderAdviceCache.forEach((clazz, methodSet) -> {
			// 该Controller 满足 ControllerAdvice 的处理条件
			if (clazz.isApplicableToBeanType(handlerType)) {
				// 获取 ControllerAdvice 的那个类
				Object bean = clazz.resolveBean();
				for (Method method : methodSet) {
					// 将 controlleradvice 与 携带 @InitBinder 注解的方法 生成 initBinderMethod 对象
					initBinderMethods.add(createInitBinderMethod(bean, method));
				}
			}
		});
		// 遍历 controller 上的全部 携带 @InitBinder 注解的 方法 添加  (说明了 @ControllerAdvice 的优先级 比 @Controller 的优先级高)
		for (Method method : methods) {
			Object bean = handlerMethod.getBean();
			initBinderMethods.add(createInitBinderMethod(bean, method));
		}
		return createDataBinderFactory(initBinderMethods);
	}

	/**
	 * 将 ControllerAdvice 类 与 携带 @InitBinder 注解的 方法 整合成一个对象
	 * @param bean
	 * @param method
	 * @return
	 */
	private InvocableHandlerMethod createInitBinderMethod(Object bean, Method method) {
		InvocableHandlerMethod binderMethod = new InvocableHandlerMethod(bean, method);
		// 设置针对 initBinder的参数解析器
		if (this.initBinderArgumentResolvers != null) {
			binderMethod.setHandlerMethodArgumentResolvers(this.initBinderArgumentResolvers);
		}
		// 设置数据绑定工厂
		binderMethod.setDataBinderFactory(new DefaultDataBinderFactory(this.webBindingInitializer));
		// 设置参数名生成器
		binderMethod.setParameterNameDiscoverer(this.parameterNameDiscoverer);
		return binderMethod;
	}

	/**
	 * Template method to create a new InitBinderDataBinderFactory instance.
	 * <p>The default implementation creates a ServletRequestDataBinderFactory.
	 * This can be overridden for custom ServletRequestDataBinder subclasses.
	 * @param binderMethods {@code @InitBinder} methods
	 * @return the InitBinderDataBinderFactory instance to use
	 * @throws Exception in case of invalid state or arguments
	 * 将一组 可执行方法生成一个 数据绑定工厂
	 */
	protected InitBinderDataBinderFactory createDataBinderFactory(List<InvocableHandlerMethod> binderMethods)
			throws Exception {

		return new ServletRequestDataBinderFactory(binderMethods, getWebBindingInitializer());
	}

	/**
	 * 最后生成 ModelAndView 对象
	 * @param mavContainer  相当于是 mvc 的上下文对象 内部存放了 调用方法 生成的model 信息
	 * @param modelFactory
	 * @param webRequest
	 * @return
	 * @throws Exception
	 */
	@Nullable
	private ModelAndView getModelAndView(ModelAndViewContainer mavContainer,
			ModelFactory modelFactory, NativeWebRequest webRequest) throws Exception {

		// 将 mavc 保存到 modelFactory 中 默认情况都是 以 session 为 单位保存数据 这在 tomcat 层实现
		modelFactory.updateModel(webRequest, mavContainer);
		// 如果判断已经完成处理了 就返回null  一些 ReturnValueResolver 会修改该标识 不走 ModelAndView 的渲染逻辑
		if (mavContainer.isRequestHandled()) {
			return null;
		}
		// 返回所有存放的数据
		ModelMap model = mavContainer.getModel();
		// 传入视图名 和 数据 返回的 HttpStatus  如果 之前handle 方法 是否返回ModelAndView 这里都新建了一个 并将 之前的结果转移到了该对象
		// 这样返回的 ModelAndView 对象 就可以保存数据了 如果 mavContainer.isRequestHandled() 为 true 之后拦截器的后置函数 传入的ModelAndView 就是null 就取不到想要的数据
		ModelAndView mav = new ModelAndView(mavContainer.getViewName(), model, mavContainer.getStatus());
		// 代表 mavc 中存放的 不是视图名 而是一个视图对象 就直接 设置到 ModelAndView 中
		if (!mavContainer.isViewReference()) {
			mav.setView((View) mavContainer.getView());
		}
		// 如果是重定向 需要将属性存入到 flashMap 中 并保存到 req 现在 只看到 直接返回 RedirectAttributes 中 有新建该对象 应该是很少用了
		if (model instanceof RedirectAttributes) {
			Map<String, ?> flashAttributes = ((RedirectAttributes) model).getFlashAttributes();
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			if (request != null) {
				// 保存到 req 中代表是单次的 而重定向会维持这些属性 但是第二次 req 就不会携带这些属性  session 的话 只要针对同一cookie 就能在多个请求中传递
				RequestContextUtils.getOutputFlashMap(request).putAll(flashAttributes);
			}
		}
		return mav;
	}

}
