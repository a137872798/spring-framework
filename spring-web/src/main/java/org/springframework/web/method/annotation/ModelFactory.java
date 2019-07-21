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

package org.springframework.web.method.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.core.Conventions;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Assist with initialization of the {@link Model} before controller method
 * invocation and with updates to it after the invocation.
 *
 * <p>On initialization the model is populated with attributes temporarily stored
 * in the session and through the invocation of {@code @ModelAttribute} methods.
 *
 * <p>On update model attributes are synchronized with the session and also
 * {@link BindingResult} attributes are added if missing.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 * 该对象 以 Controller 为 单位
 */
public final class ModelFactory {

	private static final Log logger = LogFactory.getLog(ModelFactory.class);

	/**
	 * 内部维护的 携带@ModelAttribute 的方法
	 */
	private final List<ModelMethod> modelMethods = new ArrayList<>();

	/**
	 * 数据绑定工厂 根据传入的对象生成一个针对该对象的 绑定属性
	 */
	private final WebDataBinderFactory dataBinderFactory;

	/**
	 * 内部维护了 对应Controller @SessionAttribute 中 name value
	 */
	private final SessionAttributesHandler sessionAttributesHandler;


	/**
	 * Create a new instance with the given {@code @ModelAttribute} methods.
	 * @param handlerMethods the {@code @ModelAttribute} methods to invoke
	 * @param binderFactory for preparation of {@link BindingResult} attributes
	 * @param attributeHandler for access to session attributes
	 */
	public ModelFactory(@Nullable List<InvocableHandlerMethod> handlerMethods,
			WebDataBinderFactory binderFactory, SessionAttributesHandler attributeHandler) {

		if (handlerMethods != null) {
			for (InvocableHandlerMethod handlerMethod : handlerMethods) {
				// 将 InvocableHandlerMethod 封装成 ModelMethod 对象
				this.modelMethods.add(new ModelMethod(handlerMethod));
			}
		}
		this.dataBinderFactory = binderFactory;
		this.sessionAttributesHandler = attributeHandler;
	}


	/**
	 * Populate the model in the following order:
	 * <ol>
	 * <li>Retrieve "known" session attributes listed as {@code @SessionAttributes}.
	 * <li>Invoke {@code @ModelAttribute} methods
	 * <li>Find {@code @ModelAttribute} method arguments also listed as
	 * {@code @SessionAttributes} and ensure they're present in the model raising
	 * an exception if necessary.
	 * </ol>
	 * @param request the current request   HttpServletRequest  HttpServletResponse 整合的对象
	 * @param container a container with the model to be initialized  modelAndView的包装对象 在整个handle 处理过程中它作为桥梁
	 * @param handlerMethod the method for which the model is initialized    handle 方法
	 * @throws Exception may arise from {@code @ModelAttribute} methods
	 * 初始化Model 对象
	 */
	public void initModel(NativeWebRequest request, ModelAndViewContainer container, HandlerMethod handlerMethod)
			throws Exception {

		// 从request 对象中 获取某些属性  对应到就是在 Controller 类上 设置的 @SessionAttribute 注解内的数据  在 调用某个handle 方法时 会直接注入到 Model 对象中
		Map<String, ?> sessionAttributes = this.sessionAttributesHandler.retrieveAttributes(request);
		// 将属性 集成到 Model 这个map 中
		container.mergeAttributes(sessionAttributes);
		// 执行所有携带@ModelAttribute 的方法 并将结果保存到 model 中
		invokeModelAttributeMethods(request, container);

		// 目标方法中 携带@ModelAttribute 注解的"参数" 且 包含在 Controller 上@SessionAttribute 中
		for (String name : findSessionAttributeArguments(handlerMethod)) {
			// 一般情况下 下面的 代码是等价于  		Map<String, ?> sessionAttributes = this.sessionAttributesHandler.retrieveAttributes(request); 的
			// 但是 findSessionAttributeArguments 除了匹配 @SessionAttribute 的 name 外 还会匹配与@ModelAttribute修饰参数的 type
			if (!container.containsAttribute(name)) {
				// 将值从req 转移到 model 中
				Object value = this.sessionAttributesHandler.retrieveAttribute(request, name);
				if (value == null) {
					throw new HttpSessionRequiredException("Expected session attribute '" + name + "'", name);
				}
				container.addAttribute(name, value);
			}
		}
	}

	/**
	 * Invoke model attribute methods to populate the model.
	 * Attributes are added only if not already present in the model.
	 * 执行 携带@ModelAttribute 的 方法 并将结果保存到model 中  携带@ModelAttribute 的方法 代表该方法就是用来生成注解中标识的name 的
	 */
	private void invokeModelAttributeMethods(NativeWebRequest request, ModelAndViewContainer container)
			throws Exception {

		// modelMethods 是从 handle 对应类中 所有携带 @ModelAttribute 的方法 以及 适用于该Controller  的 ControllerAdvice 中携带的@ModelAttribute 集成的
		while (!this.modelMethods.isEmpty()) {
			// 这里应该是从 中选出一个合适的方法 也就是 @ModelAttribute 都能在 本次 model 中寻找到
			InvocableHandlerMethod modelMethod = getNextModelMethod(container).getHandlerMethod();
			// 获取方法级别的 ModelAttribute
			ModelAttribute ann = modelMethod.getMethodAnnotation(ModelAttribute.class);
			Assert.state(ann != null, "No ModelAttribute annotation");
			// 如果 mavc 中已经存在了 目标 name 代表不在需要调用该方法 并生成这个属性了
			if (container.containsAttribute(ann.name())) {
				// 代表 该值不需要被绑定
				if (!ann.binding()) {
					container.setBindingDisabled(ann.name());
				}
				// 这里进入了下次 循环 更换了一个 modelMethod
				continue;
			}

			// 执行 modelAttribute 方法  这里还没有真正执行handler 方法吧 在执行前 提前将同一控制器下所有携带@ModelAttribute 的方法都执行了一遍
			Object returnValue = modelMethod.invokeForRequest(request, container);
			// 代表 该方法存在返回值
			if (!modelMethod.isVoid()){
				// 生成返回值名
				String returnValueName = getNameForReturnValue(returnValue, modelMethod.getReturnType());
				// 代表该值不需要被绑定
				if (!ann.binding()) {
					container.setBindingDisabled(returnValueName);
				}
				// model中还不存在该属性时 设置到 model 中
				if (!container.containsAttribute(returnValueName)) {
					container.addAttribute(returnValueName, returnValue);
				}
			}
		}
	}

	/**
	 * 从mavc 中选择一个合适的 带 @ModelAttribute 的方法
	 * @param container
	 * @return
	 */
	private ModelMethod getNextModelMethod(ModelAndViewContainer container) {
		// 遍历所有的 携带 @ModelAttribute 注解的方法
		for (ModelMethod modelMethod : this.modelMethods) {
			// 保证 @ModelAttribute 中需要的属性 本次req 中都能提供
			if (modelMethod.checkDependencies(container)) {
				// 移除该方法
				this.modelMethods.remove(modelMethod);
				return modelMethod;
			}
		}
		// 都找不到匹配的就返回第一个方法
		ModelMethod modelMethod = this.modelMethods.get(0);
		this.modelMethods.remove(modelMethod);
		return modelMethod;
	}

	/**
	 * Find {@code @ModelAttribute} arguments also listed as {@code @SessionAttributes}.
	 * 从handle 方法上寻找 ModelAttribute 修饰的 参数
	 */
	private List<String> findSessionAttributeArguments(HandlerMethod handlerMethod) {
		List<String> result = new ArrayList<>();
		for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
			if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
				// 获取参数名 默认是从 注解上获取参数名
				String name = getNameForParameter(parameter);
				// 获取该参数的类型
				Class<?> paramType = parameter.getParameterType();
				// 判断是否存在于 @SessionAttribute 注解中
				if (this.sessionAttributesHandler.isHandlerSessionAttribute(name, paramType)) {
					result.add(name);
				}
			}
		}
		return result;
	}

	/**
	 * Promote model attributes listed as {@code @SessionAttributes} to the session.
	 * Add {@link BindingResult} attributes where necessary.
	 * @param request the current request
	 * @param container contains the model to update
	 * @throws Exception if creating BindingResult attributes fails
	 * 更新model 中的数据
	 */
	public void updateModel(NativeWebRequest request, ModelAndViewContainer container) throws Exception {
		ModelMap defaultModel = container.getDefaultModel();
		if (container.getSessionStatus().isComplete()){
			// 本次会话结束了 就清除
			this.sessionAttributesHandler.cleanupAttributes(request);
		}
		else {
			// 否则就是保存 新的属性
			this.sessionAttributesHandler.storeAttributes(request, defaultModel);
		}
		if (!container.isRequestHandled() && container.getModel() == defaultModel) {
			// 将数据保存到 bindresult中
			updateBindingResult(request, defaultModel);
		}
	}

	/**
	 * Add {@link BindingResult} attributes to the model for attributes that require it.
	 * 每次更新model 时 会触发该方法  model中每个name 对应一个 databinder 对象
	 */
	private void updateBindingResult(NativeWebRequest request, ModelMap model) throws Exception {
		List<String> keyNames = new ArrayList<>(model.keySet());
		for (String name : keyNames) {
			Object value = model.get(name);
			if (value != null && isBindingCandidate(name, value)) {
				String bindingResultKey = BindingResult.MODEL_KEY_PREFIX + name;
				// 将字段包装长城 DataBinder 并保存
				if (!model.containsAttribute(bindingResultKey)) {
					WebDataBinder dataBinder = this.dataBinderFactory.createBinder(request, value, name);
					model.put(bindingResultKey, dataBinder.getBindingResult());
				}
			}
		}
	}

	/**
	 * Whether the given attribute requires a {@link BindingResult} in the model.
	 * 判断 该 属性是否 需要设置对应的 bindind 对象
	 */
	private boolean isBindingCandidate(String attributeName, Object value) {
		// 应该是代表已经 生成了对应的属性了
		if (attributeName.startsWith(BindingResult.MODEL_KEY_PREFIX)) {
			return false;
		}

		if (this.sessionAttributesHandler.isHandlerSessionAttribute(attributeName, value.getClass())) {
			return true;
		}

		// 即使是 非 @SessionAttribute 注解修饰的字段 只要是 简单类型也算符合条件
		return (!value.getClass().isArray() && !(value instanceof Collection) &&
				!(value instanceof Map) && !BeanUtils.isSimpleValueType(value.getClass()));
	}


	/**
	 * Derive the model attribute name for the given method parameter based on
	 * a {@code @ModelAttribute} parameter annotation (if present) or falling
	 * back on parameter type based conventions.
	 * @param parameter a descriptor for the method parameter
	 * @return the derived name
	 * @see Conventions#getVariableNameForParameter(MethodParameter)
	 */
	public static String getNameForParameter(MethodParameter parameter) {
		ModelAttribute ann = parameter.getParameterAnnotation(ModelAttribute.class);
		String name = (ann != null ? ann.value() : null);
		return (StringUtils.hasText(name) ? name : Conventions.getVariableNameForParameter(parameter));
	}

	/**
	 * Derive the model attribute name for the given return value. Results will be
	 * based on:
	 * <ol>
	 * <li>the method {@code ModelAttribute} annotation value
	 * <li>the declared return type if it is more specific than {@code Object}
	 * <li>the actual return value type
	 * </ol>
	 * @param returnValue the value returned from a method invocation
	 * @param returnType a descriptor for the return type of the method
	 * @return the derived name (never {@code null} or empty String)
	 */
	public static String getNameForReturnValue(@Nullable Object returnValue, MethodParameter returnType) {
		ModelAttribute ann = returnType.getMethodAnnotation(ModelAttribute.class);
		if (ann != null && StringUtils.hasText(ann.value())) {
			return ann.value();
		}
		else {
			Method method = returnType.getMethod();
			Assert.state(method != null, "No handler method");
			Class<?> containingClass = returnType.getContainingClass();
			Class<?> resolvedType = GenericTypeResolver.resolveReturnType(method, containingClass);
			return Conventions.getVariableNameForReturnType(method, resolvedType, returnValue);
		}
	}


	private static class ModelMethod {

		private final InvocableHandlerMethod handlerMethod;

		/**
		 * 携带 @ModelAttribute 注解的参数名
		 */
		private final Set<String> dependencies = new HashSet<>();

		public ModelMethod(InvocableHandlerMethod handlerMethod) {
			this.handlerMethod = handlerMethod;
			// 获取该方法中携带 @ModelAttribute 的参数
			for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
				if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
					this.dependencies.add(getNameForParameter(parameter));
				}
			}
		}

		public InvocableHandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public boolean checkDependencies(ModelAndViewContainer mavContainer) {
			for (String name : this.dependencies) {
				if (!mavContainer.containsAttribute(name)) {
					return false;
				}
			}
			return true;
		}

		public List<String> getUnresolvedDependencies(ModelAndViewContainer mavContainer) {
			List<String> result = new ArrayList<>(this.dependencies.size());
			for (String name : this.dependencies) {
				if (!mavContainer.containsAttribute(name)) {
					result.add(name);
				}
			}
			return result;
		}

		@Override
		public String toString() {
			return this.handlerMethod.getMethod().toGenericString();
		}
	}

}
