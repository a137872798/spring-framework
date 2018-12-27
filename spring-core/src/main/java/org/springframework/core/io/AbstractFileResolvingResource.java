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

package org.springframework.core.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;

import org.springframework.util.ResourceUtils;

/**
 * Abstract base class for resources which resolve URLs into File references,
 * such as {@link UrlResource} or {@link ClassPathResource}.
 *
 * <p>Detects the "file" protocol as well as the JBoss "vfs" protocol in URLs,
 * resolving file system references accordingly.
 *
 * @author Juergen Hoeller
 * @since 3.0
 *
 * 该对象 是 解析了 URL 对象的 资源对象
 */
public abstract class AbstractFileResolvingResource extends AbstractResource {

	@Override
	public boolean exists() {
		try {
			URL url = getURL();
			//是文件 资源的 情况下 照常处理
			if (ResourceUtils.isFileURL(url)) {
				// Proceed with file system resolution
				return getFile().exists();
			}
			else {
				//这里处理的是 非 FileResource 的 对象

				// Try a URL connection content-length header
				// 创建资源连接对象
				URLConnection con = url.openConnection();
				customizeConnection(con);
				//这里 代表是 一个网络资源 url 就是 一个域名
				HttpURLConnection httpCon =
						(con instanceof HttpURLConnection ? (HttpURLConnection) con : null);
				//如果创建的connection 是 Http类型的
				if (httpCon != null) {
					//获取 响应信息
					int code = httpCon.getResponseCode();
					//请求 成功 代表通过该 网络 url 成功 获取到了资源信息
					if (code == HttpURLConnection.HTTP_OK) {
						return true;
					}
					else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
						return false;
					}
				}
				//非 HttpUrl 的情况 只要 通过 url 连接到资源后 能获取到长度就代表存在该资源
				if (con.getContentLengthLong() > 0) {
					return true;
				}
				if (httpCon != null) {
					// No HTTP OK status, and no content-length header: give up
					// 非 200 且 非 404 直接关闭 因为没法确认情况
					httpCon.disconnect();
					return false;
				}
				else {
					// Fall back to stream existence: can we open the stream?
					// 关闭资源系统
					getInputStream().close();
					return true;
				}
			}
		}
		catch (IOException ex) {
			return false;
		}
	}

	/**
	 * 资源是否可读
	 * @return
	 */
	@Override
	public boolean isReadable() {
		try {
			URL url = getURL();
			//是文件 url 正常处理
			if (ResourceUtils.isFileURL(url)) {
				// Proceed with file system resolution
				File file = getFile();
				return (file.canRead() && !file.isDirectory());
			}
			else {
				// Try InputStream resolution for jar resources
				URLConnection con = url.openConnection();
				customizeConnection(con);
				if (con instanceof HttpURLConnection) {
					HttpURLConnection httpCon = (HttpURLConnection) con;
					int code = httpCon.getResponseCode();
					//不是 200 代表 资源不可访问就是不可读
					if (code != HttpURLConnection.HTTP_OK) {
						httpCon.disconnect();
						return false;
					}
				}
				//获取文件长度
				long contentLength = con.getContentLengthLong();
				if (contentLength > 0) {
					return true;
				}
				else if (contentLength == 0) {
					// Empty file or directory -> not considered readable...
					return false;
				}
				else {
					// Fall back to stream existence: can we open the stream?
					getInputStream().close();
					return true;
				}
			}
		}
		catch (IOException ex) {
			return false;
		}
	}

	@Override
	public boolean isFile() {
		try {
			URL url = getURL();
			if (url.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				return VfsResourceDelegate.getResource(url).isFile();
			}
			//判断是否是 文件协议对象
			return ResourceUtils.URL_PROTOCOL_FILE.equals(url.getProtocol());
		}
		catch (IOException ex) {
			return false;
		}
	}

	/**
	 * This implementation returns a File reference for the underlying class path
	 * resource, provided that it refers to a file in the file system.
	 * @see org.springframework.util.ResourceUtils#getFile(java.net.URL, String)
	 */
	@Override
	public File getFile() throws IOException {
		URL url = getURL();
		//如果 是 vfs 协议 就返回 特殊的 file
		if (url.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
			return VfsResourceDelegate.getResource(url).getFile();
		}
		return ResourceUtils.getFile(url, getDescription());
	}

	/**
	 * This implementation determines the underlying File
	 * (or jar file, in case of a resource in a jar/zip).
	 * 获取文件最后修改时间
	 */
	@Override
	protected File getFileForLastModifiedCheck() throws IOException {
		URL url = getURL();
		if (ResourceUtils.isJarURL(url)) {
			URL actualUrl = ResourceUtils.extractArchiveURL(url);
			if (actualUrl.getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				return VfsResourceDelegate.getResource(actualUrl).getFile();
			}
			return ResourceUtils.getFile(actualUrl, "Jar URL");
		}
		else {
			return getFile();
		}
	}

	/**
	 * This implementation returns a File reference for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @since 5.0
	 * @see #getFile(URI)
	 */
	protected boolean isFile(URI uri) {
		try {
			if (uri.getScheme().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
				return VfsResourceDelegate.getResource(uri).isFile();
			}
			return ResourceUtils.URL_PROTOCOL_FILE.equals(uri.getScheme());
		}
		catch (IOException ex) {
			return false;
		}
	}

	/**
	 * This implementation returns a File reference for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @see org.springframework.util.ResourceUtils#getFile(java.net.URI, String)
	 */
	protected File getFile(URI uri) throws IOException {
		if (uri.getScheme().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
			return VfsResourceDelegate.getResource(uri).getFile();
		}
		return ResourceUtils.getFile(uri, getDescription());
	}

	/**
	 * This implementation returns a FileChannel for the given URI-identified
	 * resource, provided that it refers to a file in the file system.
	 * @since 5.0
	 * @see #getFile()
	 */
	@Override
	public ReadableByteChannel readableChannel() throws IOException {
		try {
			// Try file system channel
			// 使用java.nio 实现
			return FileChannel.open(getFile().toPath(), StandardOpenOption.READ);
		}
		catch (FileNotFoundException | NoSuchFileException ex) {
			// Fall back to InputStream adaptation in superclass
			return super.readableChannel();
		}
	}

	/**
	 * 获取 内容长度
	 * @return
	 * @throws IOException
	 */
	@Override
	public long contentLength() throws IOException {
		URL url = getURL();
		if (ResourceUtils.isFileURL(url)) {
			// Proceed with file system resolution
			File file = getFile();
			long length = file.length();
			if (length == 0L && !file.exists()) {
				throw new FileNotFoundException(getDescription() +
						" cannot be resolved in the file system for checking its content length");
			}
			return length;
		}
		else {
			// Try a URL connection content-length header
			URLConnection con = url.openConnection();
			customizeConnection(con);
			return con.getContentLengthLong();
		}
	}

	@Override
	public long lastModified() throws IOException {
		URL url = getURL();
		boolean fileCheck = false;
		if (ResourceUtils.isFileURL(url) || ResourceUtils.isJarURL(url)) {
			// Proceed with file system resolution
			fileCheck = true;
			try {
				File fileToCheck = getFileForLastModifiedCheck();
				long lastModified = fileToCheck.lastModified();
				if (lastModified > 0L || fileToCheck.exists()) {
					return lastModified;
				}
			}
			catch (FileNotFoundException ex) {
				// Defensively fall back to URL connection check instead
			}
		}
		// Try a URL connection last-modified header
		URLConnection con = url.openConnection();
		customizeConnection(con);
		long lastModified = con.getLastModified();
		if (fileCheck && lastModified == 0 && con.getContentLengthLong() <= 0) {
			throw new FileNotFoundException(getDescription() +
					" cannot be resolved in the file system for checking its last-modified timestamp");
		}
		return lastModified;
	}

	/**
	 * Customize the given {@link URLConnection}, obtained in the course of an
	 * {@link #exists()}, {@link #contentLength()} or {@link #lastModified()} call.
	 * <p>Calls {@link ResourceUtils#useCachesIfNecessary(URLConnection)} and
	 * delegates to {@link #customizeConnection(HttpURLConnection)} if possible.
	 * Can be overridden in subclasses.
	 * @param con the URLConnection to customize
	 * @throws IOException if thrown from URLConnection methods
	 *
	 * 传入资源连接对象
	 */
	protected void customizeConnection(URLConnection con) throws IOException {
		//设置 是否需要使用缓存
		ResourceUtils.useCachesIfNecessary(con);
		//如果 资源是 HTTP 资源对象
		if (con instanceof HttpURLConnection) {
			customizeConnection((HttpURLConnection) con);
		}
	}

	/**
	 * Customize the given {@link HttpURLConnection}, obtained in the course of an
	 * {@link #exists()}, {@link #contentLength()} or {@link #lastModified()} call.
	 * <p>Sets request method "HEAD" by default. Can be overridden in subclasses.
	 * @param con the HttpURLConnection to customize
	 * @throws IOException if thrown from HttpURLConnection methods
	 */
	protected void customizeConnection(HttpURLConnection con) throws IOException {
		//如果是 Http url 连接对象 设置 requestMethod 为 HEAD
		con.setRequestMethod("HEAD");
	}


	/**
	 * Inner delegate class, avoiding a hard JBoss VFS API dependency at runtime.
	 *
	 * vsf 资源解析对象
	 */
	private static class VfsResourceDelegate {

		public static Resource getResource(URL url) throws IOException {
			return new VfsResource(VfsUtils.getRoot(url));
		}

		public static Resource getResource(URI uri) throws IOException {
			return new VfsResource(VfsUtils.getRoot(uri));
		}
	}

}
