/*
 * Copyright 2002-2017 the original author or authors.
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

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.SmartView;
import org.springframework.web.servlet.View;

/**
 * Handles return values of type {@link ModelAndView} copying view and model
 * information to the {@link ModelAndViewContainer}.
 *
 * <p>If the return value is {@code null}, the
 * {@link ModelAndViewContainer#setRequestHandled(boolean)} flag is set to
 * {@code true} to indicate the request was handled directly.
 *
 * <p>A {@link ModelAndView} return type has a set purpose. Therefore this
 * handler should be configured ahead of handlers that support any return
 * value type annotated with {@code @ModelAttribute} or {@code @ResponseBody}
 * to ensure they don't take over.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 * 代表 返回值 就是 ModelAndView 对象
 */
public class ModelAndViewMethodReturnValueHandler implements HandlerMethodReturnValueHandler {

	@Nullable
	private String[] redirectPatterns;


	/**
	 * Configure one more simple patterns (as described in {@link PatternMatchUtils#simpleMatch})
	 * to use in order to recognize custom redirect prefixes in addition to "redirect:".
	 * <p>Note that simply configuring this property will not make a custom redirect prefix work.
	 * There must be a custom {@link View} that recognizes the prefix as well.
	 * @since 4.1
	 */
	public void setRedirectPatterns(@Nullable String... redirectPatterns) {
		this.redirectPatterns = redirectPatterns;
	}

	/**
	 * Return the configured redirect patterns, if any.
	 * @since 4.1
	 */
	@Nullable
	public String[] getRedirectPatterns() {
		return this.redirectPatterns;
	}


	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return ModelAndView.class.isAssignableFrom(returnType.getParameterType());
	}

	/**
	 * 处理返回值是 ModelAndView 的情况
	 * @param returnValue the value returned from the handler method
	 * @param returnType the type of the return value. This type must have
	 * previously been passed to {@link #supportsReturnType} which must
	 * have returned {@code true}.
	 * @param mavContainer the ModelAndViewContainer for the current request
	 * @param webRequest the current request
	 * @throws Exception
	 */
	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
			ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

		// 如果没有返回值 也就不需要 处理ModelAndView 了 因为这里已经显式指定了 返回值就是 ModelAndView 对象
		if (returnValue == null) {
			mavContainer.setRequestHandled(true);
			return;
		}

		ModelAndView mav = (ModelAndView) returnValue;
		// 如果 mav 中存放的 是视图名
		if (mav.isReference()) {
			String viewName = mav.getViewName();
			mavContainer.setViewName(viewName);
			if (viewName != null && isRedirectViewName(viewName)) {
				// 代表重定向
				mavContainer.setRedirectModelScenario(true);
			}
		}
		else {
			View view = mav.getView();
			mavContainer.setView(view);
			if (view instanceof SmartView && ((SmartView) view).isRedirectView()) {
				mavContainer.setRedirectModelScenario(true);
			}
		}
		mavContainer.setStatus(mav.getStatus());
		mavContainer.addAllAttributes(mav.getModel());
	}

	/**
	 * Whether the given view name is a redirect view reference.
	 * The default implementation checks the configured redirect patterns and
	 * also if the view name starts with the "redirect:" prefix.
	 * @param viewName the view name to check, never {@code null}
	 * @return "true" if the given view name is recognized as a redirect view
	 * reference; "false" otherwise.
	 */
	protected boolean isRedirectViewName(String viewName) {
		return (PatternMatchUtils.simpleMatch(this.redirectPatterns, viewName) || viewName.startsWith("redirect:"));
	}

}
