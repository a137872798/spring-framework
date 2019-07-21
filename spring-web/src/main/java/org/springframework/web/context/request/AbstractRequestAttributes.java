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

package org.springframework.web.context.request;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.Assert;

/**
 * Abstract support class for RequestAttributes implementations,
 * offering a request completion mechanism for request-specific destruction
 * callbacks and for updating accessed session attributes.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #requestCompleted()
 * req Attr 的骨架类 具备 存取数据的能力
 */
public abstract class AbstractRequestAttributes implements RequestAttributes {

	/** Map from attribute name String to destruction callback Runnable. */
	protected final Map<String, Runnable> requestDestructionCallbacks = new LinkedHashMap<>(8);

	/**
	 * 代表当前 request 对象是否还有用 （请求处理完了就没用了）
	 */
	private volatile boolean requestActive = true;


	/**
	 * Signal that the request has been completed.
	 * <p>Executes all request destruction callbacks and updates the
	 * session attributes that have been accessed during request processing.
	 * 标记本次请求已经完成
	 */
	public void requestCompleted() {
		// 执行所有回调函数
		executeRequestDestructionCallbacks();
		// 更新 sessionAttribute 子类实现
		updateAccessedSessionAttributes();
		this.requestActive = false;
	}

	/**
	 * Determine whether the original request is still active.
	 * @see #requestCompleted()
	 */
	protected final boolean isRequestActive() {
		return this.requestActive;
	}

	/**
	 * Register the given callback as to be executed after request completion.
	 * @param name the name of the attribute to register the callback for
	 * @param callback the callback to be executed for destruction
	 */
	protected final void registerRequestDestructionCallback(String name, Runnable callback) {
		Assert.notNull(name, "Name must not be null");
		Assert.notNull(callback, "Callback must not be null");
		synchronized (this.requestDestructionCallbacks) {
			this.requestDestructionCallbacks.put(name, callback);
		}
	}

	/**
	 * Remove the request destruction callback for the specified attribute, if any.
	 * @param name the name of the attribute to remove the callback for
	 */
	protected final void removeRequestDestructionCallback(String name) {
		Assert.notNull(name, "Name must not be null");
		synchronized (this.requestDestructionCallbacks) {
			this.requestDestructionCallbacks.remove(name);
		}
	}

	/**
	 * Execute all callbacks that have been registered for execution
	 * after request completion.
	 */
	private void executeRequestDestructionCallbacks() {
		synchronized (this.requestDestructionCallbacks) {
			for (Runnable runnable : this.requestDestructionCallbacks.values()) {
				runnable.run();
			}
			this.requestDestructionCallbacks.clear();
		}
	}

	/**
	 * Update all session attributes that have been accessed during request processing,
	 * to expose their potentially updated state to the underlying session manager.
	 */
	protected abstract void updateAccessedSessionAttributes();

}
