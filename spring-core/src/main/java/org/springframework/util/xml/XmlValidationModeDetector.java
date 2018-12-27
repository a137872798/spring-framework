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

package org.springframework.util.xml;

import java.io.BufferedReader;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Detects whether an XML stream is using DTD- or XSD-based validation.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 *
 *
 * 校验 该 xml 文件是否符合 DTD or XSD 规范
 */
public class XmlValidationModeDetector {

	/**
	 * Indicates that the validation should be disabled.
	 *
	 * 该xml 未通过 校验
	 */
	public static final int VALIDATION_NONE = 0;

	/**
	 * Indicates that the validation mode should be auto-guessed, since we cannot find
	 * a clear indication (probably choked on some special characters, or the like).
	 */
	public static final int VALIDATION_AUTO = 1;

	/**
	 * Indicates that DTD validation should be used (we found a "DOCTYPE" declaration).
	 *
	 * DTD 模式
	 */
	public static final int VALIDATION_DTD = 2;

	/**
	 * Indicates that XSD validation should be used (found no "DOCTYPE" declaration).
	 *
	 * XSD 模式
	 */
	public static final int VALIDATION_XSD = 3;


	/**
	 * The token in a XML document that declares the DTD to use for validation
	 * and thus that DTD validation is being used.
	 *
	 * 固定的前缀
	 */
	private static final String DOCTYPE = "DOCTYPE";

	/**
	 * The token that indicates the start of an XML comment.
	 *
	 */
	private static final String START_COMMENT = "<!--";

	/**
	 * The token that indicates the end of an XML comment.
	 */
	private static final String END_COMMENT = "-->";


	/**
	 * Indicates whether or not the current parse position is inside an XML comment.
	 *
	 * 代表正处在 xml 的 注释部分中
	 */
	private boolean inComment;


	/**
	 * Detect the validation mode for the XML document in the supplied {@link InputStream}.
	 * Note that the supplied {@link InputStream} is closed by this method before returning.
	 * @param inputStream the InputStream to parse
	 * @throws IOException in case of I/O failure
	 * @see #VALIDATION_DTD
	 * @see #VALIDATION_XSD
	 *
	 * 通过 输入流 判断 校验模式
	 */
	public int detectValidationMode(InputStream inputStream) throws IOException {
		// Peek into the file to look for DOCTYPE.
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		try {
			boolean isDtdValidated = false;
			String content;
			while ((content = reader.readLine()) != null) {
				//去除掉 注释的 部分
				content = consumeCommentTokens(content);
				//正在 注释部分 或者 不存在内容 读取下一行
				if (this.inComment || !StringUtils.hasText(content)) {
					continue;
				}
				//含有DOCTYPE 代表是 DTD 格式的 xml
				if (hasDoctype(content)) {
					isDtdValidated = true;
					break;
				}
				//因为 该 xml 不是以 DOCTYPE 开头 所以 判定是XSD
				if (hasOpeningTag(content)) {
					// End of meaningful data...
					break;
				}
			}
			return (isDtdValidated ? VALIDATION_DTD : VALIDATION_XSD);
		}
		catch (CharConversionException ex) {
			// Choked on some character encoding...
			// Leave the decision up to the caller.
			return VALIDATION_AUTO;
		}
		finally {
			//关闭输入流
			reader.close();
		}
	}


	/**
	 * Does the content contain the DTD DOCTYPE declaration?
	 */
	private boolean hasDoctype(String content) {
		return content.contains(DOCTYPE);
	}

	/**
	 * Does the supplied content contain an XML opening tag. If the parse state is currently
	 * in an XML comment then this method always returns false. It is expected that all comment
	 * tokens will have consumed for the supplied content before passing the remainder to this method.
	 */
	private boolean hasOpeningTag(String content) {
		if (this.inComment) {
			return false;
		}
		//判断是否 有 < 标签
		int openTagIndex = content.indexOf('<');
		return (openTagIndex > -1 && (content.length() > openTagIndex + 1) &&
				//代表 < 后跟着的是 字母
				Character.isLetter(content.charAt(openTagIndex + 1)));
	}

	/**
	 * Consumes all the leading comment data in the given String and returns the remaining content, which
	 * may be empty since the supplied content might be all comment data. For our purposes it is only important
	 * to strip leading comment content on a line since the first piece of non comment content will be either
	 * the DOCTYPE declaration or the root element of the document.
	 */
	@Nullable
	private String consumeCommentTokens(String line) {
		//非注解 信息 直接返回
		if (!line.contains(START_COMMENT) && !line.contains(END_COMMENT)) {
			return line;
		}
		String currLine = line;
		//代表是注解信息
		//currLine 是截取后的部分
		while ((currLine = consume(currLine)) != null) {
			//非注释模式下 可以返回 截取后剩下的 内容
			if (!this.inComment && !currLine.trim().startsWith(START_COMMENT)) {
				return currLine;
			}
		}
		//代表 是 <!--  后面的 部分 那么就没有返回的必要了
		return null;
	}

	/**
	 * Consume the next comment token, update the "inComment" flag
	 * and return the remaining content.
	 */
	@Nullable
	private String consume(String line) {
		//这个 好像是 判断 正在 注释中 还是 脱离注释 来判断 <!--  or  -->
		int index = (this.inComment ? endComment(line) : startComment(line));
		return (index == -1 ? null : line.substring(index));
	}

	/**
	 * Try to consume the {@link #START_COMMENT} token.
	 * @see #commentToken(String, String, boolean)
	 */
	private int startComment(String line) {
		return commentToken(line, START_COMMENT, true);
	}

	private int endComment(String line) {
		//结束 注释状态 所以 inCommentIfPresent 设置为false
		return commentToken(line, END_COMMENT, false);
	}

	/**
	 * Try to consume the supplied token against the supplied content and update the
	 * in comment parse state to the supplied value. Returns the index into the content
	 * which is after the token or -1 if the token is not found.
	 */
	private int commentToken(String line, String token, boolean inCommentIfPresent) {
		int index = line.indexOf(token);
		if (index > - 1) {
			//如果搜索到对应的 注释 开始 符 or 结束符  更改 inComment 的状态 并返回 注释符的 起始下标
			//这里应该是单线程的 因为没有 使用 任何 防并发手段
			this.inComment = inCommentIfPresent;
		}
		return (index == -1 ? index : index + token.length());
	}

}
