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

package org.springframework.core.convert.support;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.Set;

import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Converts a comma-delimited String to an Array.
 * Only matches if String.class can be converted to the target array element type.
 *
 * @author Keith Donald
 * @author Juergen Hoeller
 * @since 3.0
 *
 * 			将字符串转换成 array 的实现类
 */
final class StringToArrayConverter implements ConditionalGenericConverter {

	private final ConversionService conversionService;


	public StringToArrayConverter(ConversionService conversionService) {
		this.conversionService = conversionService;
	}


	/**
	 * 这里是标记了 该converter 会获取到 怎样的 转换pair  这里就是  String 与数组 Object[] 的对应
	 * @return
	 */
	@Override
	public Set<ConvertiblePair> getConvertibleTypes() {
		return Collections.singleton(new ConvertiblePair(String.class, Object[].class));
	}

	/**
	 * 判断能否匹配上
	 * @param sourceType the type descriptor of the field we are converting from
	 * @param targetType the type descriptor of the field we are converting to
	 * @return
	 */
	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		return ConversionUtils.canConvertElements(sourceType, targetType.getElementTypeDescriptor(),
				this.conversionService);
	}

	/**
	 * 转换的核心方法
	 * @param source the source object to convert (may be {@code null})
	 * @param sourceType the type descriptor of the field we are converting from
	 * @param targetType the type descriptor of the field we are converting to
	 * @return
	 */
	@Override
	@Nullable
	public Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (source == null) {
			return null;
		}
		//将源数据转换成String 类型
		String string = (String) source;
		//根据分隔符","拆分
		String[] fields = StringUtils.commaDelimitedListToStringArray(string);
		//获取 目标对象的 类型信息
		TypeDescriptor targetElementType = targetType.getElementTypeDescriptor();
		Assert.state(targetElementType != null, "No target element type");
		//根据指定类型 生成对应的数组对象
		Object target = Array.newInstance(targetElementType.getType(), fields.length);
		for (int i = 0; i < fields.length; i++) {
			//获取  源数据
			String sourceElement = fields[i];
			//将源数据转换成需要的数据
			Object targetElement = this.conversionService.convert(sourceElement.trim(), sourceType, targetElementType);
			//设置到数组中
			Array.set(target, i, targetElement);
		}
		return target;
	}

}
