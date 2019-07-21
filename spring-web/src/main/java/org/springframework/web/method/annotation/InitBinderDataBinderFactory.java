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

import java.util.Collections;
import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.support.DefaultDataBinderFactory;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.InvocableHandlerMethod;

/**
 * Adds initialization to a WebDataBinder via {@code @InitBinder} methods.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 * 重写父类2个 核心方法 创建 binder 实例 和 init binder实例
 */
public class InitBinderDataBinderFactory extends DefaultDataBinderFactory {

	/**
	 * 代表 某个 Controller 上 所有携带@InitBinder 的方法 以及 符合该Controller 匹配条件的所有@ControllerAdvice 的 @InitBinder 方法
	 */
	private final List<InvocableHandlerMethod> binderMethods;


	/**
	 * Create a new InitBinderDataBinderFactory instance.
	 * @param binderMethods {@code @InitBinder} methods
	 * @param initializer for global data binder initialization
	 */
	public InitBinderDataBinderFactory(@Nullable List<InvocableHandlerMethod> binderMethods,
			@Nullable WebBindingInitializer initializer) {

		super(initializer);
		this.binderMethods = (binderMethods != null ? binderMethods : Collections.emptyList());
	}


	/**
	 * Initialize a WebDataBinder with {@code @InitBinder} methods.
	 * <p>If the {@code @InitBinder} annotation specifies attributes names,
	 * it is invoked only if the names include the target object name.
	 * @throws Exception if one of the invoked @{@link InitBinder} methods fails
	 * @see #isBinderMethodApplicable
	 * 根据 请求对象初始化binder 对象
	 */
	@Override
	public void initBinder(WebDataBinder dataBinder, NativeWebRequest request) throws Exception {
		for (InvocableHandlerMethod binderMethod : this.binderMethods) {
			// 是否符合绑定条件  一个 binder 对象只能管理一个对象的绑定 这里是看 @InitBinder 中的 处理对象数组中是否有包含 DataBinder
			if (isBinderMethodApplicable(binderMethod, dataBinder)) {
				// 调用目标方法并返回结果
				Object returnValue = binderMethod.invokeForRequest(request, null, dataBinder);
				if (returnValue != null) {
					throw new IllegalStateException(
							"@InitBinder methods must not return a value (should be void): " + binderMethod);
				}
			}
		}
	}

	/**
	 * Determine whether the given {@code @InitBinder} method should be used
	 * to initialize the given {@link WebDataBinder} instance. By default we
	 * check the specified attribute names in the annotation value, if any.
	 */
	protected boolean isBinderMethodApplicable(HandlerMethod initBinderMethod, WebDataBinder dataBinder) {
		// 获取  initBinder 的值
		InitBinder ann = initBinderMethod.getMethodAnnotation(InitBinder.class);
		Assert.state(ann != null, "No InitBinder annotation");
		String[] names = ann.value();
		return (ObjectUtils.isEmpty(names) || ObjectUtils.containsElement(names, dataBinder.getObjectName()));
	}

}
